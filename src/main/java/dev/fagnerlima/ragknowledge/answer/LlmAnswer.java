package dev.fagnerlima.ragknowledge.answer;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Structured output the model must return; its JSON Schema is sent to OpenAI. */
record LlmAnswer(
        @JsonPropertyDescription("Concise answer to the question, in the language of the question")
        String answer,
        @JsonPropertyDescription("How confident the answer is: HIGH, MEDIUM or LOW")
        Confidence confidence) {
}
