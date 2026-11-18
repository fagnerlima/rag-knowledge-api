package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiTestLauncher;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * QA acceptance tests of the documented RAG_TOP_K and RAG_SIMILARITY_THRESHOLD variables as the operator sets them:
 * the real application started the way main does, with the variables passed as command-line arguments (which take
 * precedence over the real environment), on the shared test database and without reaching OpenAI.
 */
class RetrievalConfigurationAcceptanceTest {

    @Test
    void ac7_documentedVariablesAtTheirBoundsStartTheApplicationAndReachTheRetrievalSettings() {
        try (ConfigurableApplicationContext context = run("--RAG_TOP_K=20", "--RAG_SIMILARITY_THRESHOLD=1")) {
            assertThat(context.getBean(RetrievalProperties.class)).isEqualTo(new RetrievalProperties(20, 1.0));
        }

        try (ConfigurableApplicationContext context = run("--RAG_TOP_K=1", "--RAG_SIMILARITY_THRESHOLD=0")) {
            assertThat(context.getBean(RetrievalProperties.class)).isEqualTo(new RetrievalProperties(1, 0.0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--RAG_TOP_K=0",
            "--RAG_TOP_K=21",
            "--RAG_TOP_K=-3",
            "--RAG_TOP_K=four",
            "--RAG_SIMILARITY_THRESHOLD=-0.01",
            "--RAG_SIMILARITY_THRESHOLD=1.01",
            "--RAG_SIMILARITY_THRESHOLD=high"
    })
    void ac7_outOfRangeOrInvalidRetrievalVariableStopsStartup(String invalidArgument) {
        assertThatThrownBy(() -> run(invalidArgument).close())
                .hasStackTraceContaining("rag.retrieval");
    }

    private static ConfigurableApplicationContext run(String... variables) {
        List<String> args = new ArrayList<>(List.of(variables));
        args.add("--server.port=0");
        args.addAll(List.of(PgvectorTestDatabase.datasourceArguments()));
        args.add("--spring.ai.openai.api-key=test-key-not-real");
        args.add("--spring.ai.openai.base-url=http://127.0.0.1:9");

        return RagKnowledgeApiTestLauncher.run(args.toArray(String[]::new));
    }
}
