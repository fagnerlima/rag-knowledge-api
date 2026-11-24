package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class PassageRetrieverTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final PassageRetriever retriever = new PassageRetriever(vectorStore, new RetrievalProperties(3, 0.4));

    @Test
    void searchesWithTheConfiguredTopKAndThresholdAndMapsTheChunkMetadata() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(Document.builder()
                .text("HNSW text")
                .metadata(Map.of(DocumentChunker.DOCUMENT_ID, "pgvector-hnsw",
                        DocumentChunker.TITLE, "pgvector and HNSW indexes",
                        DocumentChunker.SOURCE, "pgvector-hnsw.md",
                        DocumentChunker.CHUNK_INDEX, 2))
                .score(0.61)
                .build()));

        List<RetrievedPassage> passages = retriever.retrieve("hnsw recall");

        assertThat(passages).containsExactly(new RetrievedPassage("pgvector-hnsw", "pgvector and HNSW indexes",
                "pgvector-hnsw.md", 2, "HNSW text", 0.61));
        SearchRequest request = capturedRequest();
        assertThat(request.getQuery()).isEqualTo("hnsw recall");
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.4);
    }

    @Test
    void retrieveWithATopKKeepsTheConfiguredThreshold() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());

        retriever.retrieve("anything", 8);

        SearchRequest request = capturedRequest();
        assertThat(request.getTopK()).isEqualTo(8);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.4);
    }

    @Test
    void theDefaultTopKIsTheConfiguredOne() {
        assertThat(retriever.defaultTopK()).isEqualTo(3);
    }

    @Test
    void retrieveTopIgnoresTheThreshold() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());

        retriever.retrieveTop("anything", 7);

        SearchRequest request = capturedRequest();
        assertThat(request.getTopK()).isEqualTo(7);
        assertThat(request.getSimilarityThreshold()).isEqualTo(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
    }

    @Test
    void aChunkWithoutTheCorpusMetadataIsAKnowledgeBaseFailure() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(Document.builder()
                .text("orphan")
                .metadata(Map.of(DocumentChunker.DOCUMENT_ID, "x", DocumentChunker.TITLE, "X"))
                .build()));

        assertThatThrownBy(() -> retriever.retrieve("anything"))
                .isInstanceOf(KnowledgeUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrapsSearchFailures() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> retriever.retrieve("anything"))
                .isInstanceOf(KnowledgeUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        Mockito.verify(vectorStore).similaritySearch(captor.capture());

        return captor.getValue();
    }
}
