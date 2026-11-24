package dev.fagnerlima.ragknowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import dev.fagnerlima.ragknowledge.knowledge.PassageRetriever;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/**
 * QA acceptance tests of the {@code search_knowledge} limits as an MCP client sees them, on a server configured with
 * {@code rag.retrieval.top-k} above the tool limit and a similarity threshold the offline {@code FakeEmbeddingModel}
 * can cross only with a strong word overlap: twelve passages share six of their seven words with
 * {@link #STRONG_QUERY} (cosine about 0.9), while {@link #WEAK_QUERY} shares one word with each (about 0.13).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9",
        "rag.retrieval.similarity-threshold=0.3",
        "rag.retrieval.top-k=15"
})
@Import(PgvectorTestDatabase.class)
@DirtiesContext
class McpSearchLimitsAcceptanceTest {

    private static final double THRESHOLD = 0.3;
    private static final String STRONG_QUERY = "shared corpus words for passage number";
    private static final String WEAK_QUERY = "shared aaa bbb ccc ddd eee fff ggg";
    private static final List<String> NAMES = List.of("one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve");

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private VectorStore vectorStore;

    @Autowired
    private PassageRetriever retriever;

    private McpSyncClient client;

    @BeforeEach
    void twelvePassagesAndAConnectedClient(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM vector_store");
        List<Document> documents = new ArrayList<>();

        for (int index = 0; index < NAMES.size(); index++) {
            String name = NAMES.get(index);
            documents.add(Document.builder()
                    .text("Shared corpus words for passage number " + name + ".")
                    .metadata(Map.of("documentId", "doc-" + name, "title", "Doc " + name, "source",
                            "doc-" + name + ".md", "chunkIndex", 0))
                    .build());
        }

        vectorStore.add(documents);
        clearInvocations(vectorStore);

        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
    }

    @AfterEach
    void closeClient() {
        client.closeGracefully();
    }

    // Criterion 1: topK defaults to rag.retrieval.top-k, capped at 10.
    @Test
    void withoutTopKAConfiguredValueAboveTheLimitIsCappedAtTen() {
        CallToolResult result = call(Map.of("query", STRONG_QUERY));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(passages(result)).hasSize(10);
        assertThat(lastSearchRequest().getTopK()).isEqualTo(10);
    }

    // Criterion 1: topK 1..10, the upper bound included.
    @Test
    void topKAtTheUpperLimitIsAccepted() {
        CallToolResult result = call(Map.of("query", STRONG_QUERY, "topK", 10));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(passages(result)).hasSize(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void topKBelowTheLowerLimitIsAToolError(int topK) {
        CallToolResult result = call(Map.of("query", STRONG_QUERY, "topK", topK));

        assertThat(result.isError()).isTrue();
        assertThat(errorText(result)).startsWith(KnowledgeSearchTool.INVALID_TOP_K_MESSAGE);
    }

    // Criterion 1: the configured similarity threshold is applied by the tool.
    @Test
    void theToolSearchesWithTheConfiguredThresholdAndReturnsOnlyPassagesAboveIt() {
        CallToolResult result = call(Map.of("query", STRONG_QUERY, "topK", 3));

        assertThat(lastSearchRequest().getSimilarityThreshold()).isEqualTo(THRESHOLD);
        assertThat(passages(result)).hasSize(3)
                .allSatisfy(passage -> assertThat(((Number) passage.get("score")).doubleValue())
                        .isGreaterThanOrEqualTo(THRESHOLD));
    }

    @Test
    void passagesBelowTheThresholdAreNotReturnedEvenThoughTheyAreTheMostSimilar() {
        // Without the threshold the weak query does match a stored passage...
        assertThat(retriever.retrieveTop(WEAK_QUERY, 1)).singleElement()
                .satisfies(passage -> assertThat(passage.score()).isLessThan(THRESHOLD));

        CallToolResult result = call(Map.of("query", WEAK_QUERY));

        // ...but the tool drops it, as an empty successful result.
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(passages(result)).isEmpty();
    }

    // Criterion 1: query required, at most 1000 characters.
    @Test
    void aQueryOfExactlyOneThousandCharactersIsAccepted() {
        String query = (STRONG_QUERY + " ").repeat(40).substring(0, 1000);
        assertThat(query).hasSize(1000).doesNotEndWith(" ");

        CallToolResult result = call(Map.of("query", query));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(passages(result)).isNotEmpty();
    }

    @Test
    void aQueryOfOneThousandAndOneCharactersIsAToolErrorThatDoesNotEchoIt() {
        String query = "marker-q1001 " + "x".repeat(988);
        assertThat(query).hasSize(1001);

        CallToolResult result = call(Map.of("query", query));

        assertThat(result.isError()).isTrue();
        assertThat(errorText(result)).startsWith(KnowledgeSearchTool.INVALID_QUERY_MESSAGE)
                .doesNotContain("marker-q1001");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void aCallWithoutQueryIsAToolError() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("topK", 2);

        CallToolResult result = call(arguments);

        // The SDK validates the arguments against the input schema before the tool runs (see the contract).
        assertThat(result.isError()).isTrue();
        assertThat(errorText(result)).contains("required property 'query' not found");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    // Criterion 2: the text content carries the same JSON as the structured content.
    @Test
    void theTextContentIsTheSameJsonAsTheStructuredContent() {
        CallToolResult result = call(Map.of("query", STRONG_QUERY, "topK", 2));

        assertThat(result.content()).singleElement().isInstanceOfSatisfying(TextContent.class, text -> {
            Object parsed = JsonMapper.builder().build().readValue(text.text(), Map.class);
            assertThat(parsed).isEqualTo(result.structuredContent());
        });
    }

    private SearchRequest lastSearchRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());

        return captor.getValue();
    }

    private CallToolResult call(Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(KnowledgeSearchTool.NAME, arguments));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> passages(CallToolResult result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent()).get("passages");
    }

    private static String errorText(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .reduce("", String::concat);
    }
}
