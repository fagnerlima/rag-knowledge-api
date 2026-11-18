# API contract

Version: `v1` (path prefix `/api/v1`). Errors follow [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

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
