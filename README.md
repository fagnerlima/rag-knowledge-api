# rag-knowledge-api

[![CI](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml/badge.svg)](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml)

Knowledge API with Retrieval-Augmented Generation (RAG), built with Java 21, Spring Boot and Spring AI. Study project: it ingests small public technical documents, retrieves relevant passages and answers questions citing the source.

Status: work in progress. A small corpus of original technical notes can be ingested into PostgreSQL + pgvector and searched from the command line. The question endpoint does not use retrieval yet; that is the next step.

## Requirements

- Java 21 (the Maven wrapper downloads Maven)
- Docker with Docker Compose, to run the database and the tests
- An OpenAI API key with a spending limit you are comfortable with

## Configuration

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | Yes, to run the app | — | OpenAI credential; the app refuses to start without it. Never commit it |
| `OPENAI_CHAT_MODEL` | No | `gpt-5-mini` | Chat model |
| `OPENAI_MAX_COMPLETION_TOKENS` | No | `3000` | Output token cap per request, including the model's hidden reasoning tokens |
| `OPENAI_REASONING_EFFORT` | No | `low` | Reasoning effort; must be supported by the chosen model (reasoning models such as the gpt-5 family) |
| `SERVER_ADDRESS` | No | `127.0.0.1` | Network interface to listen on. The API has no authentication: keep it local |
| `DB_URL` | No | `jdbc:postgresql://127.0.0.1:5433/ragknowledge` | JDBC URL of PostgreSQL with the pgvector extension |
| `DB_USERNAME` | No | `ragknowledge` | Database user |
| `DB_PASSWORD` | No | `ragknowledge` | Database password. The defaults are synthetic credentials for the local `compose.yaml` database only |

Embeddings use OpenAI `text-embedding-3-small` (1536 dimensions). The model is fixed in configuration because the database column has that size: changing it needs a new migration and re-ingesting the documents ([ADR 2](docs/adr/0002-pgvector-store-with-flyway-schema.md)).

With [direnv](https://direnv.net/), copy `.envrc.example` to `.envrc`, point it to a key file outside the repository and run `direnv allow`.

## Run

Start PostgreSQL with pgvector (listens only on `127.0.0.1:5433`; data stays in a Docker volume):

```bash
docker compose up -d
```

Then start the app; Flyway creates or updates the schema at startup:

```bash
./mvnw spring-boot:run
```

`docker compose down` stops the database and keeps its data; `docker compose down -v` also deletes the data.

To use another database, set all three `DB_*` variables, put the password only in `DB_PASSWORD` (never in `DB_URL`, which can end up in logs) and require verified TLS, for example `jdbc:postgresql://db.example.org:5432/ragknowledge?sslmode=verify-full`. The `vector` extension must exist or the user must be allowed to create it (a superuser, or `rds_superuser` on Amazon RDS).

```bash
curl -s -X POST localhost:8080/api/v1/answers -H 'Content-Type: application/json' -d '{"question":"What is a Java record?"}'
```

Each request calls the OpenAI API and is billed. The question text is sent to OpenAI, a third party, under OpenAI's API data policies: do not include personal or confidential data. Questions and answers are not stored.

`/actuator/health` reports `DOWN` (HTTP 503) when the database is unreachable.

## Ingest and search the corpus

The corpus is a handful of original technical notes in [`src/main/resources/corpus`](src/main/resources/corpus), listed in `manifest.json` (`id`, `title`, `file`). They contain no personal or corporate data.

With the database running and `OPENAI_API_KEY` set, index the corpus (the application runs without the web server and exits when done):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--ingest
```

Each note is split into chunks of about 400 tokens and each chunk is embedded with OpenAI and stored with its metadata (document id, title, source file, chunk index, content hash). Running it again is safe: unchanged notes are skipped without calling OpenAI, a changed note has its chunks replaced in one transaction, and notes removed from the manifest are deleted (the corpus owns the `vector_store` table). Run one ingestion at a time. The whole corpus is about 3,400 tokens, a fraction of a cent per full ingestion.

Search the indexed chunks by meaning (one embedding call per query):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--search='How does HNSW trade recall for speed?'"
```

It prints the 4 closest chunks with their cosine similarity score, title, chunk index and source file. `--ingest` and `--search=...` can be combined; they are command-line options rather than HTTP endpoints so that the unauthenticated API offers no write or bulk-billing operation. The query text is sent to OpenAI like a question.

## Test

```bash
./mvnw test
```

Tests replace the chat and embedding models with fakes and never call OpenAI; no API key is needed. Docker must be running: tests that start the whole application use a throwaway PostgreSQL with pgvector through [Testcontainers](https://testcontainers.com/), on the same image as `compose.yaml` (the first run downloads it).

When updating the PostgreSQL image (Dependabot proposes it in `compose.yaml`), copy the same tag and digest to `PgvectorTestDatabase`; `ComposeImageTest` fails until both match.

## Documentation

- [API contract](docs/api-contract.md)
- [ADR 1: Spring AI with OpenAI native structured output](docs/adr/0001-spring-ai-openai-native-structured-output.md)
- [ADR 2: pgvector store with a Flyway-managed schema](docs/adr/0002-pgvector-store-with-flyway-schema.md)
- [Changelog](CHANGELOG.md)
