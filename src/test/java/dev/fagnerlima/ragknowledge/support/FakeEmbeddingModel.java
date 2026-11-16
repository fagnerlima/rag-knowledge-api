package dev.fagnerlima.ragknowledge.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Replaces the OpenAI embedding boundary offline: feature hashing of lowercased word tokens into
 * {@value #DIMENSIONS} buckets, L2-normalized. Texts that share words get a higher cosine similarity, which is enough
 * to prove storage and retrieval mechanics; it says nothing about semantic quality.
 *
 * <p>Like {@code metadata-mode=none} in production, only the document text is embedded.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final AtomicInteger embeddedTexts = new AtomicInteger();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(texts.size());

        for (int index = 0; index < texts.size(); index++) {
            embeddings.add(new Embedding(vectorOf(texts.get(index)), index));
        }

        embeddedTexts.addAndGet(texts.size());

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    /** Fixed, so callers never trigger the default probe call that embeds a sample text. */
    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /** Texts embedded so far, including search queries; lets tests prove what was (not) re-embedded. */
    public int embeddedTextCount() {
        return embeddedTexts.get();
    }

    static float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];

        for (String token : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (!token.isEmpty()) {
                vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1;
            }
        }

        return normalized(vector);
    }

    private static float[] normalized(float[] vector) {
        double sumOfSquares = 0;

        for (float value : vector) {
            sumOfSquares += value * value;
        }

        // A text without words stays the zero vector instead of dividing by zero into NaN.
        if (sumOfSquares == 0) {
            return vector;
        }

        float norm = (float) Math.sqrt(sumOfSquares);

        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }

        return vector;
    }
}
