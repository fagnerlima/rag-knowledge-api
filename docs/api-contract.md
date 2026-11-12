# API contract

Version: `v1` (path prefix `/api/v1`). Errors follow [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`).

## `POST /api/v1/answers`

Answers a technical question with a language model. Until retrieval (RAG) lands, the model answers from its own knowledge and `sources` is always empty.

The question text is sent to the OpenAI API, a third party, and is subject to OpenAI's API data policies. Do not include personal or confidential data. The API stores nothing.

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
  "answer": "An immutable data carrier class.",
  "confidence": "HIGH",
  "sources": []
}
```

| Field | Type | Meaning |
|---|---|---|
| `answer` | string | Never blank |
| `confidence` | `HIGH` \| `MEDIUM` \| `LOW` | The model's self-reported confidence; not a calibrated probability |
| `sources` | array of `{ documentId, title, excerpt }` | Passages that support the answer; all three fields are non-null strings. Empty means no supporting passage (always, until RAG is implemented) |

Compatibility rules for `v1`:

- New response fields may be added; clients must ignore unknown fields. Removing or renaming a field requires a new version.
- The `confidence` values are closed for `v1`: a new value requires a new version. How the value is derived may change (for example, from retrieval quality once RAG lands), keeping the order `HIGH` > `MEDIUM` > `LOW`.
- New error statuses may be added. Clients should be ready for `429`, `503` and `504`, planned with timeouts and rate limits.

### Errors

| Status | When | `type` | `title` |
|---|---|---|---|
| `400 Bad Request` | Missing, blank or too long `question`, or malformed JSON | `about:blank` (omitted from the body, as RFC 9457 allows) | `Bad Request` |
| `502 Bad Gateway` | The model call failed or its output broke the contract | see below | `Language model unavailable` |

Clients branch on `status` and `type`; `title` and `detail` are human-readable and may change. Other standard HTTP errors (`405`, `415`, `500`) may also occur. Error bodies use `application/problem+json` when the request's `Accept` header allows it.

<a id="llm-unavailable"></a>**`llm-unavailable`** (`type` = `https://github.com/fagnerlima/rag-knowledge-api/blob/main/docs/api-contract.md#llm-unavailable`): the language model could not produce an answer within the contract. Retrying is safe (the request has no side effects besides cost) but may fail again when the cause is configuration, such as an invalid key, an unknown model or exhausted quota. The body never includes upstream error details or the question.

## How the structured output works

The model's reply is constrained by a JSON Schema generated from `LlmAnswer` (fields `answer` and `confidence`) and sent as OpenAI `response_format: json_schema` in strict mode. The service still validates the parsed result (non-blank answer, known confidence) because a schema reduces, but does not remove, the risk of unusable output.
