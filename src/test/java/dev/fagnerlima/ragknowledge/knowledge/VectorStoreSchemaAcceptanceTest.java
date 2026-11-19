package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * QA acceptance checks of the Flyway V1 schema at the database level: the exact vector type and the NOT NULL
 * constraints, which the PgVectorStore schema validation does not check (it compares only column names and the vector
 * size reported by its own query).
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9"
})
@Import(PgvectorTestDatabase.class)
class VectorStoreSchemaAcceptanceTest {

    private static final String ZERO_VECTOR = "[" + "0,".repeat(1535) + "0]";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void a2_embeddingColumnIsExactlyAVectorOf1536Dimensions() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT format_type(atttypid, atttypmod)
                FROM pg_attribute
                WHERE attrelid = 'public.vector_store'::regclass AND attname = 'embedding'
                """, String.class))
                .isEqualTo("vector(1536)");
    }

    @Test
    void a2_vectorExtensionIsInstalledByTheMigration() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void a2_rowWithoutMetadataIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.vector_store (content, metadata, embedding) VALUES ('text', NULL, ?::vector)",
                ZERO_VECTOR))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"metadata\"");
    }

    @Test
    void a2_rowWithoutEmbeddingIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.vector_store (content, metadata, embedding) VALUES ('text', '{}'::jsonb, NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"embedding\"");
    }

    @Test
    void a2_rowWithoutContentIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.vector_store (content, metadata, embedding) VALUES (NULL, '{}'::jsonb, ?::vector)",
                ZERO_VECTOR))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"content\"");
    }

    @Test
    void a2_embeddingWithTheWrongDimensionsIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public.vector_store (content, metadata, embedding) VALUES ('text', '{}'::jsonb, ?::vector)",
                "[0,0,0]"))
                .hasMessageContaining("1536");
    }
}
