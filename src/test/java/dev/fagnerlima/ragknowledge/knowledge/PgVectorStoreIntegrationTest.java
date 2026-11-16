package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.fagnerlima.ragknowledge.support.FakeEmbeddingModel;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * The auto-configured PgVectorStore on the Flyway schema of a real PostgreSQL with pgvector, with only the embedding
 * model faked. Proves the storage mechanics ingestion will rely on: metadata round trip, ranking, topK, threshold,
 * idempotent upsert by deterministic id and delete by document.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9"
})
@Import(PgvectorTestDatabase.class)
class PgVectorStoreIntegrationTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @BeforeEach
    void emptyStore() {
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    @Test
    void contextStartsOnTheFlywaySchemaWithTheSchemaValidationOfThePgVectorStore() {
        assertThat(vectorStore).isInstanceOf(PgVectorStore.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void migrationCreatesNotNullColumnsWithJsonbMetadata() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'vector_store'
                """, String.class))
                .containsExactlyInAnyOrder(
                        "id uuid NO",
                        "content text NO",
                        "metadata jsonb NO",
                        "embedding USER-DEFINED NO");
    }

    @Test
    void migrationCreatesAnHnswCosineIndexOnTheEmbedding() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'vector_store'",
                String.class))
                .anySatisfy(definition -> assertThat(definition)
                        .contains("USING hnsw (embedding vector_cosine_ops)"));
    }

    // Control for the test above: validation really compares the table, so a started context means it matched.
    @Test
    void schemaValidationRejectsATableThatDoesNotMatchTheConfiguredDimensions() {
        PgVectorStore mismatched = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(768)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();

        assertThatThrownBy(mismatched::afterPropertiesSet).hasMessageContaining("768");
    }

    @Test
    void searchRanksChunksByRelatednessAndReturnsTheirMetadata() {
        // Shared words with the query: three, two and one, so the fake embeddings fix the expected order.
        vectorStore.add(List.of(
                chunk("sealed-types", "Sealed Types", 0, "the sealed classes restrict which classes may extend them"),
                chunk("pgvector-guide", "pgvector Guide", 0, "pgvector adds an hnsw index for vector similarity search"),
                chunk("java-records", "Java Records", 0, "how java records work as immutable data carriers")));

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query("how does the pgvector hnsw index work").topK(3).build());

        assertThat(results).extracting(Document::getId).containsExactly(
                chunkId("pgvector-guide", 0), chunkId("java-records", 0), chunkId("sealed-types", 0));
        assertThat(results).extracting(Document::getScore).isSortedAccordingTo((left, right) -> right.compareTo(left));
        Document best = results.getFirst();
        assertThat(best.getText()).isEqualTo("pgvector adds an hnsw index for vector similarity search");
        assertThat(best.getMetadata())
                .containsEntry("documentId", "pgvector-guide")
                .containsEntry("title", "pgvector Guide")
                .containsEntry("sourceUrl", "https://example.org/docs/pgvector-guide")
                .containsEntry("license", "CC-BY-4.0")
                .containsEntry("chunkIndex", 0);
    }

    @Test
    void searchReturnsAtMostTopKChunks() {
        vectorStore.add(List.of(
                chunk("doc", "Doc", 0, "vector search basics"),
                chunk("doc", "Doc", 1, "vector search indexes"),
                chunk("doc", "Doc", 2, "vector search tuning")));

        assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("vector search").topK(2).build()))
                .hasSize(2);
    }

    @Test
    void searchDropsChunksBelowTheSimilarityThreshold() {
        vectorStore.add(List.of(
                chunk("related", "Related", 0, "flyway migrations version the database schema"),
                chunk("unrelated", "Unrelated", 0, "sealed classes restrict inheritance")));

        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("flyway database schema migrations")
                .topK(5)
                .similarityThreshold(0.5)
                .build());

        assertThat(results).extracting(Document::getId).containsExactly(chunkId("related", 0));
    }

    @Test
    void searchWithAMetadataFilterReturnsOnlyThatDocumentsChunks() {
        vectorStore.add(List.of(
                chunk("wanted", "Wanted", 0, "vector search basics"),
                chunk("other", "Other", 0, "vector search basics and more")));

        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("vector search basics")
                .topK(5)
                .filterExpression(new FilterExpressionBuilder().eq("documentId", "wanted").build())
                .build());

        assertThat(results).extracting(Document::getId).containsExactly(chunkId("wanted", 0));
    }

    @Test
    void addingAChunkWithAnExistingIdReplacesItInsteadOfDuplicating() {
        vectorStore.add(List.of(chunk("doc", "Doc", 0, "first version of the chunk")));
        vectorStore.add(List.of(chunk("doc", "Doc", 0, "second version of the chunk")));

        assertThat(jdbcTemplate.queryForList("SELECT content FROM vector_store", String.class))
                .containsExactly("second version of the chunk");
    }

    @Test
    void deletingByDocumentIdRemovesOnlyThatDocumentsChunks() {
        vectorStore.add(List.of(
                chunk("removed", "Removed", 0, "chunk zero"),
                chunk("removed", "Removed", 1, "chunk one"),
                chunk("kept", "Kept", 0, "chunk kept")));

        vectorStore.delete(new FilterExpressionBuilder().eq("documentId", "removed").build());

        assertThat(jdbcTemplate.queryForList("SELECT id::text FROM vector_store", String.class))
                .containsExactly(chunkId("kept", 0));
    }

    private static Document chunk(String documentId, String title, int chunkIndex, String text) {
        return Document.builder()
                .id(chunkId(documentId, chunkIndex))
                .text(text)
                .metadata(Map.of(
                        "documentId", documentId,
                        "title", title,
                        "sourceUrl", "https://example.org/docs/" + documentId,
                        "license", "CC-BY-4.0",
                        "chunkIndex", chunkIndex))
                .build();
    }

    // Deterministic ids make re-ingesting a document overwrite its chunks instead of adding copies.
    private static String chunkId(String documentId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((documentId + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
