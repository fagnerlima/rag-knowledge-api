# rag-knowledge-api

[![CI](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml/badge.svg)](https://github.com/fagnerlima/rag-knowledge-api/actions/workflows/ci.yml)

Knowledge API with Retrieval-Augmented Generation (RAG), built with Java 21, Spring Boot and Spring AI. Study project: it ingests small public technical documents, retrieves relevant passages and answers questions citing the source.

Status: work in progress. A small corpus of original technical notes is ingested into PostgreSQL + pgvector, the question endpoint answers from it citing the passages it used, and AI assistants can search it through an MCP tool.

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
| `RAG_TOP_K` | No | `4` | Corpus passages sent to the model with each question (1 to 20); more passages cost more input tokens |
| `RAG_SIMILARITY_THRESHOLD` | No | `0.25` | Minimum cosine similarity (0 to 1) for a passage to be used; with none above it, the question is answered without calling the model |

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

## Ask a question

With the corpus ingested (next section) and the app running:

```bash
curl -s -X POST localhost:8080/api/v1/answers -H 'Content-Type: application/json' -d '{"question":"Why do long-running transactions cause table bloat in PostgreSQL?"}'
```

The question is embedded, the closest corpus passages are retrieved, and the model answers only from them; `sources` lists the passages it cited (document id, title and an excerpt). A question unrelated to the corpus gets a `LOW` confidence answer saying so, with no sources and no chat model call. Every question gets that answer when the corpus was never ingested, so run `--ingest` first. See the [API contract](docs/api-contract.md).

Each question costs one embedding call and, when passages are found, one chat call with about 1,600 tokens of context. The question text and the retrieved passages are sent to OpenAI, a third party, under OpenAI's API data policies: do not include personal or confidential data. Questions and answers are not stored.

`/actuator/health` reports `DOWN` (HTTP 503) when the database is unreachable.

## Search from an AI assistant (MCP)

The running app is also a [Model Context Protocol](https://modelcontextprotocol.io/) server at `http://127.0.0.1:8080/mcp` (Streamable HTTP). Its only tool, `search_knowledge`, searches the ingested corpus and returns the most relevant passages with their source (document id, title, file, chunk index and similarity score), without calling the chat model. See the [tool contract](docs/api-contract.md#mcp-tool-search_knowledge) and [ADR 4](docs/adr/0004-rag-search-as-mcp-tool.md).

Connect Claude Code to it (local scope, only for this machine) and ask about the corpus in a session:

```bash
claude mcp add --transport http rag-knowledge http://127.0.0.1:8080/mcp
```

Or try it in the [MCP Inspector](https://github.com/modelcontextprotocol/inspector) with the Streamable HTTP transport and the same URL. Other MCP clients that support Streamable HTTP connect the same way.

A call with `{"query": "Why do long-running transactions cause table bloat in PostgreSQL?", "topK": 2}` returns two passages of `postgresql-mvcc` (chunks 1 and 0, similarity 0.553 and 0.412). Each call costs one embedding call and sends the query to OpenAI, like a question. The endpoint accepts only loopback `Host` and `Origin` values, so a web page cannot reach it through DNS rebinding; it has no authentication, so keep `SERVER_ADDRESS` on loopback.

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
- [ADR 3: RAG answers with numbered passage citations](docs/adr/0003-rag-answers-with-numbered-passage-citations.md)
- [ADR 4: RAG search as an MCP tool over Streamable HTTP](docs/adr/0004-rag-search-as-mcp-tool.md)
- [Changelog](CHANGELOG.md)
