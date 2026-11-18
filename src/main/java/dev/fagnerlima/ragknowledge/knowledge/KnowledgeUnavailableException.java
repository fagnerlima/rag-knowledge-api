package dev.fagnerlima.ragknowledge.knowledge;

/** The knowledge base could not be searched: the embedding call or the vector store failed. */
public class KnowledgeUnavailableException extends RuntimeException {

    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
