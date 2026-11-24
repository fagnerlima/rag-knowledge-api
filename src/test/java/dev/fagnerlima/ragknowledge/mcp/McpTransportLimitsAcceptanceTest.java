package dev.fagnerlima.ragknowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/**
 * QA acceptance tests of the MCP transport limits over raw HTTP: Host and Origin cases the main acceptance test does
 * not cover, and the session cap. Its own context (closed afterwards), because the session cap counts every session
 * the server holds; the session test runs last so the other requests cannot hit the cap.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9",
        "rag.retrieval.top-k=3"
})
@Import(PgvectorTestDatabase.class)
@DirtiesContext
@TestMethodOrder(OrderAnnotation.class)
class McpTransportLimitsAcceptanceTest {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\","
            + "\"version\":\"1\"}}}";

    // Static: JUnit creates one instance per test method, and the server keeps the sessions across them.
    private static int openedSessions;

    @LocalServerPort
    private int port;

    // Criterion 3: a foreign Origin is rejected on DELETE too, and every loopback host form is accepted.
    @ParameterizedTest
    @Order(1)
    @CsvSource({
            "DELETE, 127.0.0.1, http://evil.example, 403",
            "DELETE, localhost, null, 403",
            "GET, localhost, null, 403",
            "POST, localhost, https://evil.example, 403",
            "POST, [::1], , 200",
            "POST, [::1], http://[::1], 200",
            "POST, localhost, http://127.0.0.1, 200"
    })
    void hostAndOriginRulesApplyToEveryMethodAndLoopbackForm(String method, String host, String origin, int status)
            throws IOException {
        assertThat(status(method, host, origin)).isEqualTo(status);
    }

    // Criterion 3: at most 100 sessions; the next initialize is refused while they are alive.
    @Test
    @Order(2)
    void theServerHoldsAtMostOneHundredSessions() throws IOException {
        // Counts the sessions the earlier tests opened too (every accepted POST is an initialize).
        while (openedSessions < 150 && status("POST", "127.0.0.1", null) == 200) {
            // status() counts each accepted initialize
        }

        assertThat(openedSessions).isEqualTo(100);
        assertThat(status("POST", "127.0.0.1", null)).isEqualTo(503);
    }

    /** Like McpSearchAcceptanceTest#status: raw socket, since java.net.http does not allow setting Host. */
    private int status(String method, String host, String origin) throws IOException {
        byte[] body = ("POST".equals(method) ? INITIALIZE : "").getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(" /mcp HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Accept: application/json, text/event-stream\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
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
            out.write(body);
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();

            int status = Integer.parseInt(statusLine.split(" ")[1]);

            if ("POST".equals(method) && status == 200) {
                openedSessions++;
            }

            return status;
        }
    }
}
