package dev.fagnerlima.ragknowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.fagnerlima.ragknowledge.knowledge.KnowledgeUnavailableException;
import dev.fagnerlima.ragknowledge.knowledge.PassageRetriever;
import dev.fagnerlima.ragknowledge.knowledge.RetrievedPassage;

class KnowledgeSearchToolTest {

    private static final RetrievedPassage PASSAGE = new RetrievedPassage("postgresql-mvcc", "PostgreSQL MVCC",
            "postgresql-mvcc.md", 1, "Long transactions keep old row versions alive.", 0.48);

    private final PassageRetriever retriever = mock(PassageRetriever.class);
    private final KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever);

    @Test
    void returnsTheWholePassagesWithTheirSourceUsingTheConfiguredTopKByDefault() {
        given(retriever.defaultTopK()).willReturn(4);
        given(retriever.retrieve("why does bloat happen", 4)).willReturn(List.of(PASSAGE));

        SearchKnowledgeResult result = tool.searchKnowledge("  why does bloat happen ", null);

        assertThat(result.passages()).containsExactly(new KnowledgePassage("postgresql-mvcc", "PostgreSQL MVCC",
                "postgresql-mvcc.md", 1, 0.48, "Long transactions keep old row versions alive."));
    }

    @Test
    void aConfiguredTopKAboveTheToolLimitIsCappedWhenTheCallerChoosesNone() {
        given(retriever.defaultTopK()).willReturn(20);

        tool.searchKnowledge("question", null);

        verify(retriever).retrieve("question", KnowledgeSearchTool.MAX_TOP_K);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, KnowledgeSearchTool.MAX_TOP_K})
    void acceptsTheTopKLimits(int topK) {
        tool.searchKnowledge("question", topK);

        verify(retriever).retrieve("question", topK);
    }

    @Test
    void nothingRelevantIsAnEmptyResult() {
        given(retriever.retrieve(anyString(), anyInt())).willReturn(List.of());

        assertThat(tool.searchKnowledge("question", 2).passages()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsAMissingOrBlankQueryWithoutSearching(String query) {
        assertThatThrownBy(() -> tool.searchKnowledge(query, null))
                .isInstanceOf(KnowledgeSearchException.class)
                .hasMessage(KnowledgeSearchTool.INVALID_QUERY_MESSAGE);
        verify(retriever, never()).retrieve(anyString(), anyInt());
    }

    @Test
    void acceptsAQueryAtTheLengthLimitAndRejectsALongerOne() {
        String atLimit = "a".repeat(KnowledgeSearchTool.MAX_QUERY_LENGTH);

        tool.searchKnowledge(atLimit, 1);

        verify(retriever).retrieve(atLimit, 1);
        assertThatThrownBy(() -> tool.searchKnowledge(atLimit + "a", 1))
                .isInstanceOf(KnowledgeSearchException.class)
                .hasMessage(KnowledgeSearchTool.INVALID_QUERY_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, KnowledgeSearchTool.MAX_TOP_K + 1})
    void rejectsATopKOutsideTheLimitsWithoutSearching(int topK) {
        assertThatThrownBy(() -> tool.searchKnowledge("question", topK))
                .isInstanceOf(KnowledgeSearchException.class)
                .hasMessage(KnowledgeSearchTool.INVALID_TOP_K_MESSAGE);
        verify(retriever, never()).retrieve(anyString(), anyInt());
    }

    @Test
    void aKnowledgeBaseFailureBecomesAGenericToolErrorWithoutTheUpstreamDetails() {
        given(retriever.retrieve(anyString(), anyInt())).willThrow(new KnowledgeUnavailableException(
                "The knowledge base search failed", new IllegalStateException("secret upstream body")));

        assertThatThrownBy(() -> tool.searchKnowledge("question", 1))
                .isInstanceOf(KnowledgeSearchException.class)
                .hasMessage(KnowledgeSearchTool.UNAVAILABLE_MESSAGE)
                .hasNoCause();
    }
}
