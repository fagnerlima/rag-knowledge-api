package dev.fagnerlima.ragknowledge.knowledge;

/** A document of the bundled corpus, as listed in its manifest, with the full text read from its file. */
public record CorpusDocument(String id, String title, String source, String text) {
}
