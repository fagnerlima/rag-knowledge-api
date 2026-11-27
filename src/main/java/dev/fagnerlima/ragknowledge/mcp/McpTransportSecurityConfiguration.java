package dev.fagnerlima.ragknowledge.mcp;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Streamable HTTP transport of the MCP server, as Spring AI auto-configures it, plus Host and Origin validation
 * and session limits.
 * The MCP specification requires servers to validate Origin against DNS rebinding: without it, a web page open in a
 * local browser could call the unauthenticated tool through a hostname that resolves to 127.0.0.1. Only loopback
 * hosts are accepted, and a request with an Origin must come from a loopback origin.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class McpTransportSecurityConfiguration {

    static final List<String> LOOPBACK_HOSTS = List.of("127.0.0.1:*", "localhost:*", "[::1]:*");

    // Without these, sessions never expire and up to 100,000 can pile up in memory (the SDK defaults).
    static final long MAX_SESSIONS = 100;
    static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(30);

    static final List<String> LOOPBACK_ORIGINS = List.of("http://127.0.0.1:*", "http://localhost:*",
            "http://[::1]:*");

    // Replaces McpServerStreamableHttpWebMvcAutoConfiguration#webMvcStreamableServerTransportProvider (it backs off
    // with @ConditionalOnMissingBean) with the same settings plus the validator; review it on Spring AI upgrades.
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .maxSessions(MAX_SESSIONS)
                .sessionIdleTimeout(SESSION_IDLE_TIMEOUT)
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(LOOPBACK_HOSTS)
                        .allowedOrigins(LOOPBACK_ORIGINS)
                        .build())
                .build();
    }
}
