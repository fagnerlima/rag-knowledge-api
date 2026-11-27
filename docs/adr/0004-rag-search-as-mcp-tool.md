# 4. RAG search as an MCP tool over Streamable HTTP

Status: proposed (2026-11-24); accepted when the pull request that adds it is merged

## Context

Agents and AI assistants (Claude Code, Codex, the MCP Inspector, a future Spring AI agent) should be able to search the corpus and cite it without going through `POST /api/v1/answers`, which also calls the chat model. The Model Context Protocol (MCP) is the standard way to offer them a tool. The application has no authentication and listens only on `127.0.0.1`; every search costs one OpenAI embedding call; ingestion stays a command-line option on purpose (ADR 2).

## Decision

- **Server:** Spring AI 2.0.2's MCP server (`spring-ai-starter-mcp-server-webmvc`, MCP Java SDK 2.1.0), `SYNC` type, Streamable HTTP transport at `/mcp` in the same web application and on the same loopback-only server as the API. Only the tools capability is offered (prompts, resources and completions are disabled). In the `--ingest`/`--search` modes there is no web server, so there is no MCP server either.
- **Tool:** one read-only tool, `search_knowledge(query, topK?)`, declared with `@McpTool` (`readOnlyHint`, `idempotentHint`, not destructive, closed world). `query` is required, stripped, not blank and at most 1,000 characters; `topK` is optional, from 1 to 10, and defaults to `rag.retrieval.top-k` (capped at 10). The configured similarity threshold applies, as for answers, so an agent gets an empty list for an unrelated question instead of noise.
- **Output:** structured content `{ "passages": [ { documentId, title, source, chunkIndex, score, text } ] }`, with the generated `outputSchema` (MCP structured content must be an object, hence the wrapper). `text` is the whole chunk, not an excerpt, because the agent grounds its answer on it. The same JSON goes in the text content for clients without structured output support.
- **Errors:** invalid arguments and knowledge base failures are tool errors (`isError: true`) with fixed messages; the query and the upstream messages are neither returned nor logged (only the cause type is logged, as in `ApiExceptionHandler`).
- **DNS rebinding:** the MCP specification requires validating `Origin`. Spring AI's auto-configuration builds the transport without a validator, so `McpTransportSecurityConfiguration` replaces its bean with the same settings (including the MCP JSON mapper), at most 100 sessions that expire after 30 idle minutes, and the SDK's `DefaultServerTransportSecurityValidator`: `Host` must be `127.0.0.1`, `localhost` or `[::1]` (any port), otherwise `421`; an `Origin`, when present, must be a loopback origin, otherwise `403`. Requests without `Origin` (non-browser clients) are accepted.

## Options rejected

- **stdio transport (the client starts the process):** opens no port, but every client session starts a JVM and a database pool, stdout must carry nothing but protocol messages (no banner or console logs), the application would need a third run mode, and the automated test would depend on the packaged jar.
- **HTTP with Server-Sent Events (the older two-endpoint transport):** replaced by Streamable HTTP in the MCP specification (2025-03-26) and deprecated in clients.
- **`@Tool` methods with a `ToolCallbackProvider`:** they work, but the MCP annotations give the output schema, the tool hints and tool errors directly; `@Tool` fits better when the application itself calls a model with tools.

## Consequences

- One more unauthenticated HTTP surface, limited to the loopback interface and to read-only search. Listening beyond loopback (`SERVER_ADDRESS`) would need authentication first, as for the API.
- Each tool call costs one embedding call (a fraction of a cent); there is no rate limit, so an agent in a loop can call it repeatedly.
- The query is sent to OpenAI to be embedded, like a question; the returned passages come from the bundled corpus only.
- The input limits are enforced by the tool and stated in the argument descriptions but are not in the generated `inputSchema` (Spring AI 2.0.2 does not generate constraints for tool method parameters); a client that validates against the schema finds out only from the tool error. Spring AI also writes each tool error message twice (message and root cause), which the contract documents.
- The transport bean copies the auto-configured one, so a Spring AI upgrade needs a check that it still matches.
- The tool's contract is documented in [docs/api-contract.md](../api-contract.md#mcp-tool-search_knowledge) and covered by tests that use the MCP Java SDK client.

## Next steps with agents (backlog)

1. An agent in the application (Spring AI `ChatClient` with tool calling) that decides when to call `search_knowledge` and answers with citations, compared with the fixed RAG pipeline of ADR 3.
2. A per-session rate limit or embedding budget for the tool before any agent runs unattended.
3. The same Host validation for the REST API and the actuator (`/api/v1/answers` does not validate `Host` today, so a DNS rebinding page could trigger billed answers); this predates the MCP server.
4. Optional stdio transport for clients that only start local processes.
5. Grounding evaluation of agent answers that use the tool (with the M3 evaluation script).
6. The AWS counterpart for the certification track: a Bedrock Knowledge Base exposed as a tool through Amazon Bedrock AgentCore Gateway (MCP), compared with this server.
