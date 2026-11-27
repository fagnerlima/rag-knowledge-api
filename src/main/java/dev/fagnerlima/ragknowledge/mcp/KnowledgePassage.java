package dev.fagnerlima.ragknowledge.mcp;

import dev.fagnerlima.ragknowledge.knowledge.RetrievedPassage;

/**
 * A corpus passage returned by {@link KnowledgeSearchTool}, with the whole chunk text (an agent grounds on it; an
 * excerpt would drop content) and where it came from.
 *
 * @param documentId the corpus document id from the manifest
 * @param title the document title
 * @param source the corpus file the passage was read from
 * @param chunkIndex the position of the passage within its document, from 0
 * @param score cosine similarity to the query, from 0 to 1
 * @param text the passage text
 */
public record KnowledgePassage(String documentId, String title, String source, int chunkIndex, double score,
        String text) {

    static KnowledgePassage from(RetrievedPassage passage) {
        return new KnowledgePassage(passage.documentId(), passage.title(), passage.source(), passage.chunkIndex(),
                passage.score(), passage.text());
    }
}
