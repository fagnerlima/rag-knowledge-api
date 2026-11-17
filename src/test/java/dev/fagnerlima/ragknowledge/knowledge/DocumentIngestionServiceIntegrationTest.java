package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import dev.fagnerlima.ragknowledge.support.FakeEmbeddingModel;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/** Idempotent ingestion against the Flyway schema of a real PostgreSQL with pgvector; only embeddings are faked. */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9"
})
@Import(PgvectorTestDatabase.class)
class DocumentIngestionServiceIntegrationTest {

    private static final String LONG_TEXT = "Tuples, snapshots and vacuum keep the database consistent. ".repeat(80);

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentChunker chunker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @BeforeEach
    void emptyStore() {
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    @Test
    void firstIngestionAddsEveryDocumentWithItsChunks() {
        CorpusDocument mvcc = document("mvcc", "MVCC", LONG_TEXT);
        CorpusDocument pods = document("pods", "Pods", "Pods group containers that share a network namespace.");

        IngestionReport report = ingestionService.ingest(List.of(mvcc, pods));

        int expectedChunks = chunker.chunk(mvcc).size() + 1;
        assertThat(report).isEqualTo(new IngestionReport(List.of("mvcc", "pods"), List.of(), List.of(), List.of(),
                expectedChunks));
        assertThat(chunkCountsByDocument()).containsExactlyInAnyOrderEntriesOf(
                Map.of("mvcc", chunker.chunk(mvcc).size(), "pods", 1));
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT metadata->>'contentHash' FROM vector_store "
                + "WHERE metadata->>'documentId' = 'mvcc'", String.class)).containsExactly(DocumentChunker.contentHash(mvcc));
    }

    @Test
    void reIngestingAnUnchangedCorpusEmbedsNothing() {
        List<CorpusDocument> corpus = List.of(document("mvcc", "MVCC", LONG_TEXT), document("pods", "Pods", "Pods."));
        ingestionService.ingest(corpus);
        int embeddedBefore = embeddingModel.embeddedTextCount();
        List<String> rowsBefore = allRows();

        IngestionReport report = ingestionService.ingest(corpus);

        assertThat(report).isEqualTo(new IngestionReport(List.of(), List.of(), List.of("mvcc", "pods"), List.of(), 0));
        assertThat(embeddingModel.embeddedTextCount()).isEqualTo(embeddedBefore);
        assertThat(allRows()).isEqualTo(rowsBefore);
    }

    @Test
    void aChangedDocumentHasAllItsChunksReplacedWithoutLeftovers() {
        ingestionService.ingest(List.of(document("mvcc", "MVCC", LONG_TEXT), document("pods", "Pods", "Pods.")));
        CorpusDocument shortened = document("mvcc", "MVCC", "MVCC in one short sentence.");

        IngestionReport report = ingestionService.ingest(List.of(shortened, document("pods", "Pods", "Pods.")));

        assertThat(report).isEqualTo(new IngestionReport(List.of(), List.of("mvcc"), List.of("pods"), List.of(), 1));
        assertThat(jdbcTemplate.queryForList(
                "SELECT content FROM vector_store WHERE metadata->>'documentId' = 'mvcc'", String.class))
                .containsExactly("MVCC in one short sentence.");
        assertThat(chunkCountsByDocument()).containsEntry("pods", 1);
    }

    @Test
    void aRetitledDocumentIsReIngested() {
        ingestionService.ingest(List.of(document("pods", "Pods", "Pods.")));

        IngestionReport report = ingestionService.ingest(List.of(document("pods", "Kubernetes Pods", "Pods.")));

        assertThat(report.updated()).containsExactly("pods");
        assertThat(jdbcTemplate.queryForList("SELECT metadata->>'title' FROM vector_store", String.class))
                .containsExactly("Kubernetes Pods");
    }

    @Test
    void documentsRemovedFromTheCorpusArePruned() {
        ingestionService.ingest(List.of(document("mvcc", "MVCC", "MVCC."), document("pods", "Pods", "Pods.")));

        IngestionReport report = ingestionService.ingest(List.of(document("pods", "Pods", "Pods.")));

        assertThat(report).isEqualTo(new IngestionReport(List.of(), List.of(), List.of("pods"), List.of("mvcc"), 0));
        assertThat(chunkCountsByDocument()).containsOnlyKeys("pods");
    }

    @Test
    void aDocumentLeftWithMixedHashesIsReIngested() {
        CorpusDocument mvcc = document("mvcc", "MVCC", LONG_TEXT);
        ingestionService.ingest(List.of(mvcc));
        jdbcTemplate.update("""
                UPDATE vector_store SET metadata = jsonb_set(metadata, '{contentHash}', '"stale"')
                WHERE metadata->>'chunkIndex' = '0'
                """);

        IngestionReport report = ingestionService.ingest(List.of(mvcc));

        assertThat(report.updated()).containsExactly("mvcc");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT metadata->>'contentHash' FROM vector_store",
                String.class)).containsExactly(DocumentChunker.contentHash(mvcc));
    }

    @Test
    void aFailedEmbeddingCallKeepsThePreviousVersionOfTheDocument() {
        ingestionService.ingest(List.of(document("mvcc", "MVCC", LONG_TEXT)));
        List<String> rowsBefore = allRows();
        DocumentIngestionService failingIngestion = new DocumentIngestionService(
                storeWith(new FailingEmbeddingModel()), chunker, jdbcTemplate, transactionTemplate);

        assertThatThrownBy(() -> failingIngestion.ingest(List.of(document("mvcc", "MVCC", "New text."))))
                .hasMessageContaining("embedding API unavailable");
        assertThat(allRows()).isEqualTo(rowsBefore);
    }

    private VectorStore storeWith(EmbeddingModel model) {
        return PgVectorStore.builder(jdbcTemplate, model)
                .dimensions(FakeEmbeddingModel.DIMENSIONS)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .build();
    }

    private Map<String, Integer> chunkCountsByDocument() {
        return jdbcTemplate.query(
                "SELECT metadata->>'documentId' AS id, count(*) AS chunks FROM vector_store GROUP BY 1",
                (java.sql.ResultSet rows) -> {
                    Map<String, Integer> counts = new java.util.HashMap<>();

                    while (rows.next()) {
                        counts.put(rows.getString("id"), rows.getInt("chunks"));
                    }

                    return counts;
                });
    }

    private List<String> allRows() {
        return jdbcTemplate.queryForList(
                "SELECT id || ' ' || md5(content) || ' ' || metadata::text FROM vector_store ORDER BY id", String.class);
    }

    private static CorpusDocument document(String id, String title, String text) {
        return new CorpusDocument(id, title, id + ".md", text);
    }

    private static final class FailingEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new IllegalStateException("embedding API unavailable");
        }

        @Override
        public float[] embed(Document document) {
            throw new IllegalStateException("embedding API unavailable");
        }

        @Override
        public int dimensions() {
            return FakeEmbeddingModel.DIMENSIONS;
        }
    }
}
