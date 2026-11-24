package dev.fagnerlima.ragknowledge.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/** Finds the corpus chunks most similar to a query (one embedding call plus one vector search). */
@Service
public class PassageRetriever {

    private final VectorStore vectorStore;
    private final RetrievalProperties properties;

    public PassageRetriever(VectorStore vectorStore, RetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /** The configured top-K chunks above the similarity threshold, most similar first; empty when none qualifies. */
    public List<RetrievedPassage> retrieve(String query) {
        return retrieve(query, properties.topK());
    }

    /** The {@code topK} chunks above the configured similarity threshold, most similar first; empty when none qualifies. */
    public List<RetrievedPassage> retrieve(String query, int topK) {
        return search(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.similarityThreshold())
                .build());
    }

    /** The configured number of chunks a question retrieves when the caller does not choose one. */
    public int defaultTopK() {
        return properties.topK();
    }

    /** The {@code topK} most similar chunks, without the configured similarity threshold. */
    public List<RetrievedPassage> retrieveTop(String query, int topK) {
        return search(SearchRequest.builder().query(query).topK(topK).build());
    }

    private List<RetrievedPassage> search(SearchRequest request) {
        try {
            return vectorStore.similaritySearch(request).stream().map(PassageRetriever::toPassage).toList();
        } catch (RuntimeException exception) {
            throw new KnowledgeUnavailableException("The knowledge base search failed", exception);
        }
    }

    // Every chunk written by DocumentChunker has this metadata; a row without it is not part of the corpus.
    private static RetrievedPassage toPassage(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        if (!(metadata.get(DocumentChunker.DOCUMENT_ID) instanceof String documentId)
                || !(metadata.get(DocumentChunker.TITLE) instanceof String title)
                || !(metadata.get(DocumentChunker.SOURCE) instanceof String source)
                || !(metadata.get(DocumentChunker.CHUNK_INDEX) instanceof Number chunkIndex)) {
            throw new IllegalStateException("A vector store chunk lacks the corpus metadata");
        }

        return new RetrievedPassage(documentId, title, source, chunkIndex.intValue(), document.getText(),
                document.getScore() == null ? 0 : document.getScore());
    }
}
