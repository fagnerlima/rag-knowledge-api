package dev.fagnerlima.ragknowledge.mcp;

import java.util.List;

/**
 * The structured output of {@link KnowledgeSearchTool}. MCP structured content must be a JSON object, so the passages
 * are wrapped instead of returned as a bare array.
 *
 * @param passages the most similar passages above the similarity threshold, most similar first; empty when none
 */
public record SearchKnowledgeResult(List<KnowledgePassage> passages) {
}
