package dev.fagnerlima.ragknowledge.knowledge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How many chunks a question retrieves and the minimum cosine similarity a chunk needs to be used as context.
 *
 * @param topK at most this many chunks are sent to the model; more context costs more input tokens
 * @param similarityThreshold chunks below this similarity are ignored, so an off-topic question gets no context
 */
@Validated
@ConfigurationProperties("rag.retrieval")
public record RetrievalProperties(
        @Min(1) @Max(20) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold) {
}
