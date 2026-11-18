# 3. RAG answers with numbered passage citations

Status: accepted (2026-11-18)

## Context

`POST /api/v1/answers` must answer from the indexed corpus and return the passages that support the answer in `sources` (`documentId`, `title`, `excerpt`), a field the `v1` contract already has. ADR 2 expected Spring AI's retrieval advisors to do the retrieval. The answer already uses OpenAI native structured output (ADR 1), and the API has no authentication, so every model call is a cost anyone on the machine can trigger.

## Decision

- **Retrieval:** `PassageRetriever` embeds the question and searches the vector store for the top 4 chunks with cosine similarity of at least 0.25 (`rag.retrieval.top-k` and `rag.retrieval.similarity-threshold`, overridable with `RAG_TOP_K` and `RAG_SIMILARITY_THRESHOLD`). Its failures (embedding call or database) become `KnowledgeUnavailableException`, mapped to `503` with the `knowledge-unavailable` problem type, separate from the model's `502`. A stored chunk without its corpus metadata (`documentId`, `title`, `source`, `chunkIndex`) is treated the same way rather than returned with empty fields.
- **No context, no model call:** when no chunk reaches the threshold, the service returns a fixed answer with `LOW` confidence and no sources. The model would only answer from its own knowledge, without a source to cite, and the call would be billed for nothing.
- **Prompt:** the system message restricts the answer to the context and treats the question and passages as data. The user message carries the passages numbered inside `<passage number="n" title="…">` tags, then the question inside `<question>` tags. Any `passage` or `question` tag written in the question, a title or a passage has its `<` replaced with `‹` (and quotes in titles become `'`), so the text cannot close or fake the delimiters; other markup such as `List<String>` is sent as written.
- **Citations:** the structured output gains `citedPassages`, the numbers of the passages that support the answer. The service maps them to `sources` in the order cited, dropping repeats and numbers outside the context, and cuts each passage to a 300-character excerpt. A missing `citedPassages` is outside the contract (`502`), like a missing answer. An answer left without any valid citation is returned with `LOW` confidence, whatever the model reported, since nothing in the corpus supports it.
- **Threshold calibration (real embeddings, 2026-11-18):** questions about the corpus scored 0.27 to 0.52 on their best chunk (Portuguese questions about English notes on the low end); a related but uncovered topic (binary search in Python) scored 0.24; unrelated questions scored below 0.05. A chunk slightly above the threshold that does not answer the question is left to the prompt rule ("say so, use LOW, cite nothing").

## Options rejected

- **`QuestionAnswerAdvisor` or `RetrievalAugmentationAdvisor`:** they join the documents into one context block, so the model cannot point to a specific passage, and the documents they used would have to be read back from the response metadata and matched to the answer. With an empty context they still call the model (with an "I don't know" prompt). An explicit retriever plus a numbered prompt is a few lines, keeps citations exact and is tested without the advisor chain.
- **Returning every retrieved chunk as a source:** simpler, but it would list passages the answer did not use, which defeats the purpose of citing.
- **Validating that the answer is grounded (for example an LLM-as-a-judge or overlap check):** useful, but a second billed call per question; left for a later evaluation step.

## Consequences

- The corpus passages, not only the question, are sent to OpenAI on each answered question: about 4 chunks of up to 400 tokens, so input cost grows by roughly 1,600 tokens per question.
- The excerpt is the start of the cited chunk, not necessarily the sentence that supports the answer.
- `confidence` is still the model's self-report; deriving it from retrieval scores is possible within the `v1` contract.
