# 1. Spring AI with OpenAI native structured output

Status: accepted (2026-11-12)

## Context

The API needs answers in a fixed shape (`answer`, `confidence`, later `sources`) so clients and the upcoming evaluation step can rely on them. The study plan chose the OpenAI API, Java 21, Spring Boot and Spring AI, with direct OpenAI API plus JDBC as the fallback.

## Decision

- Spring Boot 4.1.2 with Spring AI 2.0.2 (`spring-ai-starter-model-openai`), model `gpt-5-mini` by default (`OPENAI_CHAT_MODEL` overrides it).
- `ChatClient.entity(LlmAnswer.class)` with `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT`: Spring AI generates a JSON Schema from the record and OpenAI enforces it (`response_format: json_schema`, strict). Without the advisor, Spring AI only appends format instructions to the prompt, which the model may ignore.
- The service validates the parsed result and maps any model failure to `502` Problem Details.
- `max-completion-tokens` defaults to 3000 to cap the cost of a single request, with `reasoning-effort=low`. For reasoning models the cap counts hidden reasoning tokens as well as the answer, so a low cap cuts the JSON and causes a `502`.
- The server listens on `127.0.0.1` by default because the API has no authentication and every request is billed.

## Consequences

- Fewer malformed replies than prompt-only formatting; the domain record doubles as the schema, so changing it changes what the model is asked for.
- Tied to providers that support native structured output; other providers fall back to prompt instructions.
- Timeouts, retries and cost tracking are not tuned yet (planned for milestone M2); the library defaults apply (60-second timeout and up to 3 retries in the OpenAI client), so a failing request can take minutes before the `502`. Observed latency in the first smoke test: about 5 to 13 seconds per answer with `gpt-5-mini`.
