package dev.fagnerlima.ragknowledge.mcp;

/**
 * A {@link KnowledgeSearchTool} call that cannot be served. The MCP server returns its message to the client as a tool
 * error ({@code isError: true}), so the message is fixed text that never carries the query or upstream details.
 */
public class KnowledgeSearchException extends RuntimeException {

    public KnowledgeSearchException(String message) {
        super(message);
    }
}
