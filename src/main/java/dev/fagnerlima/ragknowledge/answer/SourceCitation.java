package dev.fagnerlima.ragknowledge.answer;

/**
 * A document passage that supports the answer. Always empty until retrieval (RAG) is implemented,
 * but already part of the contract so clients do not break when sources start being returned.
 */
public record SourceCitation(String documentId, String title, String excerpt) {
}
