package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiTestLauncher;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * The --ingest and --search options as the operator runs them: the real application builder and the bundled corpus,
 * on the shared test database, with the fake embedding model so nothing reaches OpenAI.
 */
@ExtendWith(OutputCaptureExtension.class)
class KnowledgeCommandLineAcceptanceTest {

    private static final String[] BUNDLED_CORPUS = {
            "pgvector-hnsw", "postgresql-mvcc", "kubernetes-pods", "twelve-factor", "spring-boot-configuration"};

    // A random port, so the cleanup context never clashes with an API already running on the default port.
    @BeforeEach
    void emptyStore() {
        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            context.getBean(JdbcTemplate.class).update("DELETE FROM vector_store");
        }
    }

    @Test
    void ingestIndexesTheBundledCorpusWithoutStartingTheWebServer(CapturedOutput output) {
        try (ConfigurableApplicationContext context = run("--ingest")) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBean(JdbcTemplate.class).queryForList(
                    "SELECT DISTINCT metadata->>'documentId' FROM vector_store", String.class))
                    .containsExactlyInAnyOrder(BUNDLED_CORPUS);
        }

        assertThat(output).contains("Ingestion finished: 5 added [" + String.join(", ", BUNDLED_CORPUS) + "]");
    }

    @Test
    void ingestingTwiceReportsEveryDocumentUnchanged(CapturedOutput output) {
        run("--ingest").close();

        run("--ingest").close();

        assertThat(output).contains("0 added [], 0 updated [], 5 unchanged, 0 removed []; 0 chunks embedded");
    }

    @Test
    void searchPrintsTheMostRelatedChunksWithTheirSource(CapturedOutput output) {
        run("--ingest").close();

        run("--search=How does an HNSW index trade recall for latency in pgvector?").close();

        assertThat(output).contains("Top 4 chunks for \"How does an HNSW index trade recall for latency in pgvector?\":");
        String firstResult = output.getOut().lines()
                .dropWhile(line -> !line.startsWith("Top 4 chunks"))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertThat(firstResult).matches("\\d\\.\\d{3}  pgvector and HNSW indexes \\(chunk \\d, pgvector-hnsw\\.md\\)");
    }

    @Test
    void ingestAndSearchCanRunTogether(CapturedOutput output) {
        run("--ingest", "--search=kubernetes pod probes").close();

        assertThat(output).contains("Ingestion finished: 5 added").contains("Top 4 chunks for \"kubernetes pod probes\"");
    }

    @Test
    void aBlankSearchStopsWithAClearMessage() {
        assertThatThrownBy(() -> run("--search=   ").close())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use --search=<query> once, with a non-blank query");
    }

    @Test
    void aValueOnIngestIsRejected() {
        assertThatThrownBy(() -> run("--ingest=false").close())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use --ingest without a value");
    }

    @Test
    void aSearchWithoutQueryStopsWithAClearMessage() {
        assertThatThrownBy(() -> run("--search").close())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use --search=<query> once, with a non-blank query");
    }

    @Test
    void everyFormOfTheCommandOptionsDisablesTheWebServer() {
        assertThat(KnowledgeCommandLine.isCommand("--ingest")).isTrue();
        assertThat(KnowledgeCommandLine.isCommand("--ingest=false")).isTrue();
        assertThat(KnowledgeCommandLine.isCommand("--search")).isTrue();
        assertThat(KnowledgeCommandLine.isCommand("--search=pods")).isTrue();
        assertThat(KnowledgeCommandLine.isCommand("--server.port=0", "ingest", "-ingest")).isFalse();
    }

    @Test
    void withoutACommandTheApplicationServesTheApi() {
        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
        }
    }

    private static ConfigurableApplicationContext run(String... commandArgs) {
        List<String> args = new ArrayList<>(List.of(commandArgs));
        args.addAll(List.of(PgvectorTestDatabase.datasourceArguments()));
        args.add("--spring.ai.openai.api-key=test-key-not-real");
        args.add("--spring.ai.openai.base-url=http://127.0.0.1:9");

        return RagKnowledgeApiTestLauncher.run(args.toArray(String[]::new));
    }
}
