# rag-knowledge-api

[![CI](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml/badge.svg)](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml)

Knowledge API with Retrieval-Augmented Generation (RAG), built with Java 21, Spring Boot and Spring AI. Study project: it ingests small public technical documents, retrieves relevant passages and answers questions citing the source.

Status: work in progress. The question endpoint works without retrieval yet; ingestion with pgvector comes next.

## Requirements

- Java 21 (the Maven wrapper downloads Maven)
- An OpenAI API key with a spending limit you are comfortable with

## Configuration

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | Yes, to run the app | — | OpenAI credential; the app refuses to start without it. Never commit it |
| `OPENAI_CHAT_MODEL` | No | `gpt-5-mini` | Chat model |
| `OPENAI_MAX_COMPLETION_TOKENS` | No | `3000` | Output token cap per request, including the model's hidden reasoning tokens |
| `OPENAI_REASONING_EFFORT` | No | `low` | Reasoning effort; must be supported by the chosen model (reasoning models such as the gpt-5 family) |
| `SERVER_ADDRESS` | No | `127.0.0.1` | Network interface to listen on. The API has no authentication: keep it local |

With [direnv](https://direnv.net/), copy `.envrc.example` to `.envrc`, point it to a key file outside the repository and run `direnv allow`.

## Run

```bash
./mvnw spring-boot:run
```

```bash
curl -s -X POST localhost:8080/api/v1/answers -H 'Content-Type: application/json' -d '{"question":"What is a Java record?"}'
```

Each request calls the OpenAI API and is billed. The question text is sent to OpenAI, a third party, under OpenAI's API data policies: do not include personal or confidential data. The app stores nothing.

## Test

```bash
./mvnw test
```

Tests replace the model with a fake and make no network calls; no API key is needed.

## Documentation

- [API contract](docs/api-contract.md)
- [ADR 1: Spring AI with OpenAI native structured output](docs/adr/0001-spring-ai-openai-native-structured-output.md)
- [Changelog](CHANGELOG.md)
