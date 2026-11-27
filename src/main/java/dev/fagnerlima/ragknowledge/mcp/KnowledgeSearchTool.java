package dev.fagnerlima.ragknowledge.mcp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import dev.fagnerlima.ragknowledge.knowledge.KnowledgeUnavailableException;
import dev.fagnerlima.ragknowledge.knowledge.PassageRetriever;
import dev.fagnerlima.ragknowledge.knowledge.RetrievedPassage;

/**
 * The {@value #NAME} MCP tool: semantic search over the ingested corpus, returning whole passages with their source so
 * an agent can ground its answer and cite it. Read-only and served only on the loopback interface (see ADR 4); every
 * call costs one embedding request, so the query length and the number of passages are capped.
 */
@Component
public class KnowledgeSearchTool {

    public static final String NAME = "search_knowledge";

    static final int MAX_QUERY_LENGTH = 1000;
    static final int MAX_TOP_K = 10;

    static final String INVALID_QUERY_MESSAGE =
            "query must be a non-blank text of at most " + MAX_QUERY_LENGTH + " characters";
    static final String INVALID_TOP_K_MESSAGE = "topK must be between 1 and " + MAX_TOP_K;
    static final String UNAVAILABLE_MESSAGE = "The knowledge base could not be searched. Try again later.";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private final PassageRetriever retriever;

    public KnowledgeSearchTool(PassageRetriever retriever) {
        this.retriever = retriever;
    }

    @McpTool(name = NAME, title = "Search the technical knowledge base",
            description = "Searches a small local knowledge base of technical notes (topics such as PostgreSQL "
                    + "MVCC, pgvector and HNSW, Kubernetes Pods, Twelve-Factor apps and Spring Boot configuration) "
                    + "for the passages most relevant to a question. Use it before answering about these topics, "
                    + "and cite the documentId and title of the passages you use. The notes are in English: write "
                    + "the query in English, as a specific question or keywords. Returns each passage with its "
                    + "source (document id, title, file and chunk index) and its cosine similarity (0 to 1). "
                    + "Returns no passages when nothing in the knowledge base is relevant.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(title = "Search the technical knowledge base",
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchKnowledgeResult searchKnowledge(
            @McpToolParam(description = "The question or topic to search for, in natural language (at most "
                    + MAX_QUERY_LENGTH + " characters)") String query,
            @McpToolParam(required = false, description = "How many passages to return at most, from 1 to "
                    + MAX_TOP_K + "; defaults to the server's configured value") Integer topK) {
        String validQuery = validQuery(query);
        int validTopK = validTopK(topK);

        try {
            List<RetrievedPassage> passages = retriever.retrieve(validQuery, validTopK);

            return new SearchKnowledgeResult(passages.stream().map(KnowledgePassage::from).toList());
        } catch (KnowledgeUnavailableException exception) {
            // Like ApiExceptionHandler: never log the query or the upstream messages, only what failed.
            log.warn("{} (cause: {})", exception.getMessage(),
                    exception.getCause() == null ? "none" : exception.getCause().getClass().getName());

            throw new KnowledgeSearchException(UNAVAILABLE_MESSAGE);
        }
    }

    private static String validQuery(String query) {
        if (query == null || query.isBlank() || query.strip().length() > MAX_QUERY_LENGTH) {
            throw new KnowledgeSearchException(INVALID_QUERY_MESSAGE);
        }

        return query.strip();
    }

    private int validTopK(Integer topK) {
        if (topK == null) {
            return Math.min(retriever.defaultTopK(), MAX_TOP_K);
        }

        if (topK < 1 || topK > MAX_TOP_K) {
            throw new KnowledgeSearchException(INVALID_TOP_K_MESSAGE);
        }

        return topK;
    }
}
