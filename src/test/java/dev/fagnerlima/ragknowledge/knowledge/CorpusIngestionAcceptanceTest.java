package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiTestLauncher;
import dev.fagnerlima.ragknowledge.support.FakeEmbeddingModel;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * QA acceptance tests for the corpus ingestion criteria, run through the real application builder on the shared
 * pgvector test database with the fake embedding model (nothing reaches OpenAI). Synthetic corpora live in a temporary
 * directory and are selected with {@code knowledge.corpus.manifest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorpusIngestionAcceptanceTest {

    private static final Pattern RESULT_LINE = Pattern.compile("^(\\d\\.\\d{3})  (.+) \\(chunk (\\d+), (.+\\.md)\\)$");

    @TempDir
    private Path corpusDirectory;

    @BeforeEach
    void emptyStore() {
        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            context.getBean(JdbcTemplate.class).update("DELETE FROM vector_store");
        }
    }

    // Criterion 1: every chunk of the five bundled documents is stored with its embedding and complete metadata.
    @Test
    void criterion1_everyBundledChunkIsStoredWithCompleteMetadata() {
        try (ConfigurableApplicationContext context = run("--ingest")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DocumentChunker chunker = context.getBean(DocumentChunker.class);
            List<CorpusDocument> corpus = context.getBean(CorpusLoader.class).load();

            assertThat(corpus).hasSize(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE embedding IS NULL", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM vector_store
                    WHERE NOT (metadata ?& array['documentId', 'title', 'source', 'chunkIndex', 'contentHash'])
                    """, Integer.class)).isZero();

            for (CorpusDocument document : corpus) {
                int expectedChunks = chunker.chunk(document).size();
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT id::text AS id, metadata->>'title' AS title, metadata->>'source' AS source,
                               (metadata->>'chunkIndex')::int AS chunk_index, metadata->>'contentHash' AS hash
                        FROM vector_store WHERE metadata->>'documentId' = ? ORDER BY chunk_index
                        """, document.id());

                assertThat(rows).as(document.id()).hasSize(expectedChunks);
                assertThat(rows).extracting(row -> row.get("chunk_index"))
                        .containsExactlyElementsOf(IntStream.range(0, expectedChunks).boxed().toList());
                assertThat(rows).extracting(row -> row.get("id"))
                        .containsExactlyElementsOf(IntStream.range(0, expectedChunks)
                                .mapToObj(index -> DocumentChunker.chunkId(document.id(), index)).toList());
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.get("title")).isEqualTo(document.title());
                    assertThat(row.get("source")).isEqualTo(document.source());
                    assertThat(row.get("hash")).isEqualTo(DocumentChunker.contentHash(document));
                });
            }
        }
    }

    // Criterion 2: --search prints ranked chunks (best first), each with score, title, chunk index and source.
    @Test
    void criterion2_searchPrintsChunksRankedByDescendingScoreWithTheirMetadata(CapturedOutput output) {
        run("--ingest").close();

        run("--search=What does MVCC keep for concurrent transactions in PostgreSQL?").close();

        List<Matcher> results = output.getOut().lines()
                .dropWhile(line -> !line.startsWith("Top 4 chunks"))
                .map(RESULT_LINE::matcher)
                .filter(Matcher::matches)
                .toList();
        assertThat(results).hasSize(KnowledgeCommandLine.SEARCH_TOP_K);
        List<Double> scores = results.stream().map(result -> Double.parseDouble(result.group(1))).toList();
        assertThat(scores).isSortedAccordingTo((first, second) -> Double.compare(second, first));
        assertThat(results.getFirst().group(2)).isEqualTo("PostgreSQL MVCC");
        assertThat(results.getFirst().group(4)).isEqualTo("postgresql-mvcc.md");
    }

    // Criterion 3, end to end: on re-ingestion only the changed document is embedded, it is fully replaced and the
    // document dropped from the manifest is pruned.
    @Test
    void criterion3_reIngestionEmbedsOnlyTheChangedDocumentReplacesItAndPrunesTheRemovedOne(CapturedOutput output)
            throws IOException {
        writeNote("alpha-note", "Alpha note about synthetic vectors. ".repeat(120));
        writeNote("beta-note", "Beta note about synthetic tuples. ".repeat(120));
        writeNote("gamma-note", "Gamma note about synthetic pods.");
        writeManifest("alpha-note", "beta-note", "gamma-note");
        run("--ingest", manifestArgument()).close();

        writeNote("beta-note", "Beta note rewritten in one sentence.");
        writeManifest("alpha-note", "beta-note");

        try (ConfigurableApplicationContext context = run("--ingest", manifestArgument())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            assertThat(context.getBean(FakeEmbeddingModel.class).embeddedTextCount()).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'documentId' = 'beta-note'",
                    String.class)).containsExactly("Beta note rewritten in one sentence.");
            assertThat(jdbc.queryForList("SELECT DISTINCT metadata->>'documentId' FROM vector_store", String.class))
                    .containsExactlyInAnyOrder("alpha-note", "beta-note");
        }

        assertThat(output).contains(
                "0 added [], 1 updated [beta-note], 1 unchanged, 1 removed [gamma-note]; 1 chunks embedded");
    }

    // Criterion 3, safety: an invalid manifest stops the run before anything is embedded, replaced or pruned.
    @Test
    void criterion3_anInvalidManifestLeavesTheIndexUntouched() throws IOException {
        run("--ingest").close();
        List<String> rowsBefore = allRows();
        Files.writeString(corpusDirectory.resolve("manifest.json"), """
                {"documents": [{"id": "only-note", "title": "Only", "file": "missing.md"}]}
                """);

        assertThatThrownBy(() -> run("--ingest", manifestArgument()).close())
                .hasStackTraceContaining("missing.md");
        assertThat(allRows()).isEqualTo(rowsBefore);
    }

    // Criterion 3, failure in the middle of a run: the document whose embedding call fails keeps its previous version
    // in full, and the next successful run brings it up to date.
    @Test
    void criterion3_aDocumentWhoseEmbeddingFailsMidRunKeepsItsPreviousVersion() {
        CorpusDocument firstV1 = new CorpusDocument("first-note", "First", "first-note.md", "First note, version one.");
        CorpusDocument secondV1 = new CorpusDocument("second-note", "Second", "second-note.md",
                "Second note, version one. ".repeat(120));
        CorpusDocument firstV2 = new CorpusDocument("first-note", "First", "first-note.md", "First note, version two.");
        CorpusDocument secondV2 = new CorpusDocument("second-note", "Second", "second-note.md",
                "Second note, version two, FAILMARKER.");

        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DocumentChunker chunker = context.getBean(DocumentChunker.class);
            DocumentIngestionService ingestion = context.getBean(DocumentIngestionService.class);
            ingestion.ingest(List.of(firstV1, secondV1));
            List<String> secondRowsBefore = rowsOf(jdbc, "second-note");
            DocumentIngestionService failingOnMarker = new DocumentIngestionService(
                    PgVectorStore.builder(jdbc, new FailingOnMarkerEmbeddingModel())
                            .dimensions(FakeEmbeddingModel.DIMENSIONS)
                            .distanceType(PgDistanceType.COSINE_DISTANCE)
                            .initializeSchema(false)
                            .build(),
                    chunker, jdbc, context.getBean(TransactionTemplate.class));

            assertThatThrownBy(() -> failingOnMarker.ingest(List.of(firstV2, secondV2)))
                    .hasMessageContaining("embedding API unavailable");
            assertThat(rowsOf(jdbc, "second-note")).isEqualTo(secondRowsBefore);

            CorpusDocument secondV3 = new CorpusDocument("second-note", "Second", "second-note.md",
                    "Second note, version three.");
            IngestionReport retry = ingestion.ingest(List.of(firstV2, secondV3));

            assertThat(retry.unchanged()).containsExactly("first-note");
            assertThat(retry.updated()).containsExactly("second-note");
            assertThat(jdbc.queryForList(
                    "SELECT content FROM vector_store WHERE metadata->>'documentId' = 'second-note'", String.class))
                    .containsExactly("Second note, version three.");
        }
    }

    private void writeNote(String id, String text) throws IOException {
        Files.writeString(corpusDirectory.resolve(id + ".md"), text);
    }

    private void writeManifest(String... ids) throws IOException {
        List<String> entries = new ArrayList<>();

        for (String id : ids) {
            entries.add("{\"id\": \"%s\", \"title\": \"%s\", \"file\": \"%s.md\"}".formatted(id, id, id));
        }

        Files.writeString(corpusDirectory.resolve("manifest.json"),
                "{\"documents\": [" + String.join(", ", entries) + "]}");
    }

    private String manifestArgument() {
        return "--knowledge.corpus.manifest=" + corpusDirectory.resolve("manifest.json").toUri();
    }

    private static List<String> allRows() {
        try (ConfigurableApplicationContext context = run("--server.port=0")) {
            return context.getBean(JdbcTemplate.class).queryForList(
                    "SELECT id || ' ' || md5(content) || ' ' || metadata::text || ' ' || md5(embedding::text) "
                            + "FROM vector_store ORDER BY id", String.class);
        }
    }

    private static List<String> rowsOf(JdbcTemplate jdbc, String documentId) {
        return jdbc.queryForList("SELECT id || ' ' || md5(content) || ' ' || metadata::text || ' ' "
                + "|| md5(embedding::text) FROM vector_store WHERE metadata->>'documentId' = ? ORDER BY id",
                String.class, documentId);
    }

    private static ConfigurableApplicationContext run(String... commandArgs) {
        List<String> args = new ArrayList<>(List.of(commandArgs));
        args.addAll(List.of(PgvectorTestDatabase.datasourceArguments()));
        args.add("--spring.ai.openai.api-key=test-key-not-real");
        args.add("--spring.ai.openai.base-url=http://127.0.0.1:9");

        return RagKnowledgeApiTestLauncher.run(args.toArray(String[]::new));
    }

    /** Embeds like the fake model, except for texts carrying the marker, which fail like an unavailable API. */
    private static final class FailingOnMarkerEmbeddingModel implements EmbeddingModel {

        private final FakeEmbeddingModel delegate = new FakeEmbeddingModel();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (request.getInstructions().stream().anyMatch(text -> text.contains("FAILMARKER"))) {
                throw new IllegalStateException("embedding API unavailable");
            }

            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            return call(new EmbeddingRequest(List.of(document.getText()), null)).getResults().getFirst().getOutput();
        }

        @Override
        public int dimensions() {
            return FakeEmbeddingModel.DIMENSIONS;
        }
    }
}
