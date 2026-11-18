package dev.fagnerlima.ragknowledge.knowledge;

/** A corpus chunk returned by a similarity search, with its source and cosine similarity to the query. */
public record RetrievedPassage(String documentId, String title, String source, int chunkIndex, String text,
        double score) {

    /** The text on one line, cut to at most {@code maxLength} characters plus an ellipsis. */
    public String excerpt(int maxLength) {
        String singleLine = text.replaceAll("\\s+", " ").strip();

        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength) + "…";
    }
}
