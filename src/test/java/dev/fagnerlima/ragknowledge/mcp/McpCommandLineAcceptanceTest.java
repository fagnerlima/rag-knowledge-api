package dev.fagnerlima.ragknowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import dev.fagnerlima.ragknowledge.RagKnowledgeApiTestLauncher;
import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;

/**
 * QA acceptance test of criterion 4: with the MCP starter on the classpath, the --ingest/--search command line still
 * runs without a web server, so /mcp is not served, and without a STDIO MCP transport reading the terminal. (Spring AI
 * still creates the MCP server and its Streamable HTTP transport beans in this mode, unserved.)
 */
@ExtendWith(OutputCaptureExtension.class)
class McpCommandLineAcceptanceTest {

    @Test
    void theCommandLineServesNoMcpEndpointAndReadsNoStdio(CapturedOutput output) {
        try (ConfigurableApplicationContext context = run("--search=kubernetes pod probes")) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(McpServerTransportProviderBase.class).values())
                    .noneMatch(StdioServerTransportProvider.class::isInstance);
        }

        assertThat(output.getOut()).contains("chunks for \"kubernetes pod probes\"").doesNotContain("jsonrpc");
    }

    private static ConfigurableApplicationContext run(String... commandArgs) {
        List<String> args = new ArrayList<>(List.of(commandArgs));
        args.addAll(List.of(PgvectorTestDatabase.datasourceArguments()));
        args.add("--spring.ai.openai.api-key=test-key-not-real");
        args.add("--spring.ai.openai.base-url=http://127.0.0.1:9");

        return RagKnowledgeApiTestLauncher.run(args.toArray(String[]::new));
    }
}
