package dev.fagnerlima.ragknowledge.answer;

/**
 * A corpus passage the model cited as support for the answer: the document id and title from the corpus manifest and
 * the start of the passage text.
 */
public record SourceCitation(String documentId, String title, String excerpt) {
}
