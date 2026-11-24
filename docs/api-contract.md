# API contract

Version: `v1` (path prefix `/api/v1`). The same server also offers an [MCP tool](#mcp-tool-search_knowledge) at `/mcp`. Errors follow [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

## `POST /api/v1/answers`

Answers a technical question from the indexed corpus with retrieval-augmented generation (RAG): the question retrieves the most similar corpus passages, a language model answers only from them, and the passages it cites are returned in `sources`. When no passage is related to the question, the response says so with `LOW` confidence and no sources, without calling the language model.

The question text and the retrieved passages are sent to the OpenAI API, a third party, and are subject to OpenAI's API data policies. Do not include personal or confidential data. The API stores nothing.

### Request

```json
{ "question": "What is a Java record?" }
```

| Field | Type | Rules |
|---|---|---|
| `question` | string | Required, not blank, at most 2000 characters |

### Response `200 OK`

```json
{
  "answer": "Because MVCC keeps old row versions until no running transaction can see them, so VACUUM cannot reclaim them.",
  "confidence": "HIGH",
  "sources": [
    {
      "documentId": "postgresql-mvcc",
      "title": "PostgreSQL MVCC",
      "excerpt": "## Dead tuples and VACUUM Old tuple versions do not disappear when the transaction ends. Once no running transaction can see them, they become dead tuples that still take space. `VACUUM` marks that space reusable and updates the visibility map, which lets index-only scans skip heap visits. The autov…"
    }
  ]
}
```

Without a related passage (the fixed text is always in English):

```json
{
  "answer": "The knowledge base has no passage related to this question, so it cannot be answered from its sources.",
  "confidence": "LOW",
  "sources": []
}
```

| Field | Type | Meaning |
|---|---|---|
| `answer` | string | Never blank |
| `confidence` | `HIGH` \| `MEDIUM` \| `LOW` | The model's self-reported confidence, not a calibrated probability. Always `LOW` when `sources` is empty |
| `sources` | array of `{ documentId, title, excerpt }` | Corpus passages the model cited as support, in the order cited; all three fields are non-null strings. `documentId` and `title` identify the corpus note; `excerpt` is the start of the cited passage, on one line, at most 300 characters plus `…`. The same note may appear more than once (different passages). Empty means no passage supports the answer |

Compatibility rules for `v1`:

- New response fields may be added; clients must ignore unknown fields. Removing or renaming a field requires a new version.
- The `confidence` values are closed for `v1`: a new value requires a new version. How the value is derived may change (it is already forced to `LOW` when no passage is cited, and may later use retrieval scores), keeping the order `HIGH` > `MEDIUM` > `LOW`.
- New error statuses may be added. Clients should be ready for `429`, `503` and `504`, planned with timeouts and rate limits.

### Errors

| Status | When | `type` | `title` |
|---|---|---|---|
| `400 Bad Request` | Missing, blank or too long `question`, or malformed JSON | `about:blank` (omitted from the body, as RFC 9457 allows) | `Bad Request` |
| `502 Bad Gateway` | The model call failed or its output broke the contract | see below | `Language model unavailable` |
| `503 Service Unavailable` | The knowledge base could not be searched (the embedding call or the database failed, or a stored chunk lacks its corpus metadata) | see below | `Knowledge base unavailable` |

Clients branch on `status` and `type`; `title` and `detail` are human-readable and may change. Other standard HTTP errors (`405`, `415`, `500`) may also occur. Error bodies use `application/problem+json` when the request's `Accept` header allows it.

<a id="llm-unavailable"></a>**`llm-unavailable`** (`type` = `https://github.com/fagnerlima/rag-knowledge-api/blob/main/docs/api-contract.md#llm-unavailable`): the language model could not produce an answer within the contract. Retrying is safe (the request has no side effects besides cost) but may fail again when the cause is configuration, such as an invalid key, an unknown model or exhausted quota. The body never includes upstream error details or the question.

<a id="knowledge-unavailable"></a>**`knowledge-unavailable`** (`type` = `https://github.com/fagnerlima/rag-knowledge-api/blob/main/docs/api-contract.md#knowledge-unavailable`): the passages for the question could not be retrieved, so the language model was not called. Retrying is safe; the cause may be the database being down or the embeddings call failing (including configuration problems such as an invalid key). When a stored chunk lacks its corpus metadata, retrying keeps failing until the corpus is re-ingested (`--ingest`) or the row is repaired. The body never includes upstream error details or the question.

## How the structured output works

The model's reply is constrained by a JSON Schema generated from `LlmAnswer` (fields `answer`, `confidence` and `citedPassages`, the numbers of the context passages that support the answer) and sent as OpenAI `response_format: json_schema` in strict mode. The service still validates the parsed result (non-blank answer, known confidence, cited numbers present) because a schema reduces, but does not remove, the risk of unusable output. Cited numbers that do not match a passage are ignored, and an answer left without any valid citation is returned with `LOW` confidence. The question and the passages are sent inside `<question>` and `<passage>` tags; any such tag written in them has its `<` replaced with `‹`, so they cannot close or fake the delimiters. See [ADR 3](adr/0003-rag-answers-with-numbered-passage-citations.md).

## MCP tool `search_knowledge`

The application is also a [Model Context Protocol](https://modelcontextprotocol.io/) server (Streamable HTTP at `http://127.0.0.1:8080/mcp`, server name `rag-knowledge`) with one read-only tool that searches the indexed corpus and returns passages with their source. It does not call the chat model. The query is sent to OpenAI to be embedded, like a question. See [ADR 4](adr/0004-rag-search-as-mcp-tool.md).

### Input

| Argument | Type | Rules |
|---|---|---|
| `query` | string | Required, not blank, at most 1,000 characters (surrounding spaces are removed) |
| `topK` | integer | Optional, 1 to 10; defaults to `RAG_TOP_K` (capped at 10) |

### Output

Structured content (with an `outputSchema` in `tools/list`), repeated as JSON in the text content:

```json
{
  "passages": [
    {
      "documentId": "postgresql-mvcc",
      "title": "PostgreSQL MVCC",
      "source": "postgresql-mvcc.md",
      "chunkIndex": 1,
      "score": 0.48,
      "text": "## Dead tuples and VACUUM\n\nOld tuple versions do not disappear when the transaction ends. …"
    }
  ]
}
```

Passages are the most similar ones with cosine similarity of at least `RAG_SIMILARITY_THRESHOLD`, most similar first; `text` is the whole passage. An unrelated query returns `"passages": []`.

### Errors

Tool errors (`isError: true`). For the cases below the text is a fixed message, which Spring AI writes twice, on two lines (the exception message, then its root cause's):

| Case | Message |
|---|---|
| Blank or too long `query` | `query must be a non-blank text of at most 1000 characters` |
| `topK` outside 1 to 10 | `topK must be between 1 and 10` |
| Embedding call or database failure | `The knowledge base could not be searched. Try again later.` |

A missing `query` or an argument of the wrong type (for example `"topK": "two"`) is rejected by the MCP SDK's input schema validation before the tool runs: also a tool error, with a validation or conversion message from the SDK or Jackson that names the argument and may repeat the value sent.

The limits above are enforced by the tool and stated in the argument descriptions; the generated `inputSchema` declares the types and that `query` is required, but not the limits (Spring AI 2.0.2 does not generate constraints for tool method parameters).

The tool evolves additively: new optional arguments and new output fields may be added; removing or renaming a field, changing a type or the meaning of `score`, or making an argument required would be a new tool (for example `search_knowledge_v2`), offered alongside the old one for a while.

HTTP requests to `/mcp` (POST, GET and DELETE) with a `Host` other than `127.0.0.1`, `localhost` or `[::1]` get `421 Misdirected Request`; requests with an `Origin` that is not a loopback origin (including `Origin: null`) get `403 Forbidden` (protection against DNS rebinding). Sessions expire after 30 idle minutes, and at most 100 can be open.
