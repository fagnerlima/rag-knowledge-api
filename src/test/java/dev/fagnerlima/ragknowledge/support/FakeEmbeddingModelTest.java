package dev.fagnerlima.ragknowledge.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class FakeEmbeddingModelTest {

    private final FakeEmbeddingModel model = new FakeEmbeddingModel();

    @Test
    void sameTextGivesTheSameVectorIgnoringCaseAndPunctuation() {
        assertThat(model.embed("Vector search, with pgvector!"))
                .isEqualTo(model.embed("vector SEARCH with pgvector"))
                .hasSize(FakeEmbeddingModel.DIMENSIONS);
    }

    @Test
    void vectorsAreUnitLength() {
        assertThat(norm(model.embed("records are immutable data carriers"))).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void textWithoutWordsIsTheZeroVectorInsteadOfNaN() {
        assertThat(model.embed(" ?! ")).containsOnly(0f);
    }

    @Test
    void sharedWordsGiveAHigherCosineSimilarity() {
        float[] query = model.embed("hnsw index in pgvector");

        assertThat(dot(query, model.embed("pgvector supports an hnsw index")))
                .isGreaterThan(dot(query, model.embed("java records are immutable")));
    }

    @Test
    void embedsOnlyTheDocumentTextAndCountsEveryEmbeddedText() {
        Document document = new Document("sealed classes", Map.of("title", "Unrelated metadata"));

        assertThat(model.embed(document)).isEqualTo(model.embed("sealed classes"));
        model.embed(List.of("one", "two", "three"));

        assertThat(model.embeddedTextCount()).isEqualTo(5);
        assertThat(model.dimensions()).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
    }

    private static double norm(float[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double dot(float[] left, float[] right) {
        double sum = 0;

        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }

        return sum;
    }
}
