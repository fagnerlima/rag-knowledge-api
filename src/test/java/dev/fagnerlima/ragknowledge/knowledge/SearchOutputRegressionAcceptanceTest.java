package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiTestLauncher;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * QA regression test of AC8: the --search output keeps the exact format it had before retrieval moved to
 * PassageRetriever, including the 160-character excerpt cut. The base implementation appended the ellipsis right
 * after the first 160 characters of the one-line text, even when the last of them was a space.
 */
@ExtendWith(OutputCaptureExtension.class)
class SearchOutputRegressionAcceptanceTest {

    private static final int BASE_SEARCH_EXCERPT_LENGTH = 160;

    @Test
    void ac8_searchOutputIsUnchangedIncludingTheExcerptCutAtAWordBoundary(CapturedOutput output) {
        // 40 five-character words: the one-line text has a space at index 159, the last character before the cut.
        String text = "kiwi\n".repeat(40);

        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            context.getBean(JdbcTemplate.class).update("DELETE FROM vector_store");
            context.getBean(VectorStore.class).add(List.of(Document.builder()
                    .text(text)
                    .metadata(Map.of("documentId", "kiwi", "title", "Kiwi note", "source", "kiwi.md",
                            "chunkIndex", 3, "contentHash", "qa"))
                    .build()));
        }

        run("--search=kiwi").close();

        List<String> lines = output.getOut().lines().dropWhile(line -> !line.startsWith("Top 1 chunks")).toList();
        String baseExcerpt = text.replaceAll("\\s+", " ").strip().substring(0, BASE_SEARCH_EXCERPT_LENGTH) + "…";
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lines.get(0)).isEqualTo("Top 1 chunks for \"kiwi\":");
        assertThat(lines.get(1)).matches("1\\.000  Kiwi note \\(chunk 3, kiwi\\.md\\)");
        assertThat(lines.get(2)).isEqualTo("       " + baseExcerpt);
    }

    private static ConfigurableApplicationContext run(String... commandArgs) {
        List<String> args = new ArrayList<>(List.of(commandArgs));
        args.addAll(List.of(PgvectorTestDatabase.datasourceArguments()));
        args.add("--spring.ai.openai.api-key=test-key-not-real");
        args.add("--spring.ai.openai.base-url=http://127.0.0.1:9");

        return RagKnowledgeApiTestLauncher.run(args.toArray(String[]::new));
    }
}
