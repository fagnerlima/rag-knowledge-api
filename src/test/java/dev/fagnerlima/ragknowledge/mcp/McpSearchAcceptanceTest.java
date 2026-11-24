package dev.fagnerlima.ragknowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Acceptance tests of the {@code search_knowledge} MCP tool as a client sees it: the MCP Java SDK client talks
 * Streamable HTTP to the running application on a random port, backed by the real pgvector store and the offline
 * {@code FakeEmbeddingModel}, so nothing reaches OpenAI.
 *
 * <p>The fake embedding scores word overlap, so the threshold is lowered: a query sharing words with a passage
 * retrieves it, and one sharing none does not.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9",
        "rag.retrieval.similarity-threshold=0.05",
        "rag.retrieval.top-k=4"
})
@Import(PgvectorTestDatabase.class)
class McpSearchAcceptanceTest {

    private static final String MVCC_TEXT = "Long running transactions keep old row versions alive and cause table bloat.";
    private static final String HNSW_TEXT = "HNSW indexes trade recall for speed in approximate vector search.";

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private VectorStore vectorStore;

    private McpSyncClient client;

    @BeforeEach
    void twoPassagesAndAConnectedClient(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM vector_store");
        vectorStore.add(List.of(passage("postgresql-mvcc", "PostgreSQL MVCC", "postgresql-mvcc.md", 0, MVCC_TEXT),
                passage("pgvector-hnsw", "pgvector and HNSW indexes", "pgvector-hnsw.md", 2, HNSW_TEXT)));

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

    @Test
    void theServerOffersOnlyTheReadOnlySearchToolWithInputAndOutputSchemas() {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactly(KnowledgeSearchTool.NAME);
        Tool tool = tools.getFirst();
        assertThat(tool.description()).contains("knowledge base");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.annotations().destructiveHint()).isFalse();
        assertThat(tool.inputSchema()).containsEntry("required", List.of("query"));
        assertThat(objectAt(tool.inputSchema(), "properties")).containsOnlyKeys("query", "topK");
        assertThat(tool.outputSchema()).isNotNull();
        assertThat(objectAt(tool.outputSchema(), "properties")).containsOnlyKeys("passages");
    }

    @Test
    void aCallReturnsTheRelevantPassagesWithTheirSourceAsStructuredAndTextContent() {
        CallToolResult result = call(Map.of("query", "why do long running transactions cause bloat"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        List<Map<String, Object>> passages = passages(result);
        assertThat(passages).isNotEmpty();
        assertThat(passages.getFirst())
                .containsEntry("documentId", "postgresql-mvcc")
                .containsEntry("title", "PostgreSQL MVCC")
                .containsEntry("source", "postgresql-mvcc.md")
                .containsEntry("chunkIndex", 0)
                .containsEntry("text", MVCC_TEXT)
                .containsKey("score");
        // Clients without structured output support read the same result as text.
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(TextContent.class,
                text -> assertThat(text.text()).contains("postgresql-mvcc", "PostgreSQL MVCC"));
    }

    @Test
    void topKLimitsTheNumberOfPassages() {
        CallToolResult result = call(Map.of("query", "transactions indexes search speed bloat", "topK", 1));

        assertThat(passages(result)).hasSize(1);
    }

    @Test
    void aQueryUnrelatedToTheCorpusReturnsNoPassages() {
        CallToolResult result = call(Map.of("query", "quando florescem os ipês amarelos"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(passages(result)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'   '|4|query must be a non-blank text of at most 1000 characters",
            "bloat|11|topK must be between 1 and 10"
    })
    void invalidArgumentsAreToolErrors(String query, int topK, String message) {
        CallToolResult result = call(Map.of("query", query, "topK", topK));

        assertThat(result.isError()).isTrue();
        // Spring AI writes the exception message, then the root cause's: the same fixed text twice (see the contract).
        assertThat(errorText(result)).isEqualTo(message + System.lineSeparator() + message);
    }

    @Test
    void anArgumentOfTheWrongTypeIsAToolError() {
        CallToolResult result = call(Map.of("query", "bloat", "topK", "two"));

        assertThat(result.isError()).isTrue();
        assertThat(errorText(result)).contains("topK");
    }

    @Test
    void aKnowledgeBaseFailureIsAGenericToolErrorWithoutTheQueryOrUpstreamDetails() {
        doThrow(new IllegalStateException("upstream body marker-7f3a"))
                .when(vectorStore).similaritySearch(any(SearchRequest.class));

        CallToolResult result = call(Map.of("query", "query marker-9c2b"));

        assertThat(result.isError()).isTrue();
        assertThat(errorText(result)).isEqualTo(KnowledgeSearchTool.UNAVAILABLE_MESSAGE + System.lineSeparator()
                        + KnowledgeSearchTool.UNAVAILABLE_MESSAGE)
                .doesNotContain("marker-7f3a", "marker-9c2b");
    }

    @ParameterizedTest
    @CsvSource({
            // DNS rebinding: a foreign name resolved to 127.0.0.1 (421 Misdirected Request), on every MCP method.
            "POST, evil.example, , 421",
            "GET, evil.example, , 421",
            "DELETE, evil.example, , 421",
            // A web page on another site calling the local server from the browser (403 Forbidden).
            "POST, 127.0.0.1, http://evil.example, 403",
            "POST, 127.0.0.1, http://127.0.0.1.evil.example, 403",
            "GET, 127.0.0.1, http://evil.example, 403",
            // Sandboxed frames and file:// pages send Origin: null.
            "POST, 127.0.0.1, null, 403"
    })
    void requestsWithAForeignHostOrOriginAreRejected(String method, String host, String origin, int status)
            throws IOException {
        assertThat(status(method, host, origin)).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({
            "127.0.0.1, ",
            "localhost, http://localhost",
            "127.0.0.1, http://127.0.0.1"
    })
    void requestsFromLoopbackAreAccepted(String host, String origin) throws IOException {
        assertThat(status("POST", host, origin)).isEqualTo(200);
    }

    /**
     * Sends an MCP request over a raw socket, since java.net.http does not allow setting Host, and returns the HTTP
     * status: an initialize request for POST, no body otherwise. A null origin sends no Origin header, like a
     * non-browser client; the text {@code null} sends {@code Origin: null}.
     */
    private int status(String method, String host, String origin) throws IOException {
        String body = "POST".equals(method) ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\","
                + "\"version\":\"1\"}}}" : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(" /mcp HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Accept: application/json, text/event-stream\r\n")
                .append("Content-Length: ").append(bodyBytes.length).append("\r\n")
                .append("Connection: close\r\n");

        if ("null".equals(origin)) {
            request.append("Origin: null\r\n");
        } else if (origin != null) {
            request.append("Origin: ").append(origin).append(':').append(port).append("\r\n");
        }

        request.append("\r\n");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bodyBytes);
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();

            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private CallToolResult call(Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(KnowledgeSearchTool.NAME, arguments));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> passages(CallToolResult result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent()).get("passages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectAt(Map<String, Object> schema, String key) {
        return (Map<String, Object>) schema.get(key);
    }

    private static String errorText(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .reduce("", String::concat);
    }

    private static Document passage(String documentId, String title, String source, int chunkIndex, String text) {
        return Document.builder()
                .text(text)
                .metadata(Map.of("documentId", documentId, "title", title, "source", source, "chunkIndex",
                        chunkIndex))
                .build();
    }
}
