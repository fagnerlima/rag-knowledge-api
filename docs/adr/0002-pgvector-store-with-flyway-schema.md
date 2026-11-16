# 2. pgvector store with a Flyway-managed schema

Status: accepted (2026-11-16)

## Context

Retrieval needs a store for document chunks, their metadata (document id, title, source URL, license, chunk index) and their embeddings, with similarity search. The study plan chose PostgreSQL with pgvector and Spring AI, whose `VectorStore` and retrieval advisors the RAG step will use. The project must stay cheap and local: no hosted database, no OpenAI call in tests.

## Decision

- **Storage:** PostgreSQL 17 with pgvector, run locally with `compose.yaml`. The image is pinned by tag and digest (`pgvector/pgvector:0.8.7-pg17@sha256:b513c485…`), published only on `127.0.0.1:5433` (5433 avoids a PostgreSQL already on the default port) with synthetic local-only credentials (`ragknowledge`), overridable with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
- **Schema:** Flyway owns it (`db/migration/V1__create_vector_store.sql`): the table Spring AI's `PgVectorStore` expects (`id uuid`, `content text`, `metadata`, `embedding vector(1536)`), schema-qualified as `public.vector_store` because that is the table the store validates. The store runs with `initialize-schema=false` and `schema-validation=true`, so it never creates or changes tables and refuses to start when the column names or the vector size do not match its settings.
- **Deliberate differences from the library's DDL:** `metadata` is `jsonb` instead of `json`, since the store already writes `?::jsonb` and filters with `metadata::jsonb @@ jsonpath`, and `jsonb` can get a GIN index later; every column is `NOT NULL`; the id default is the built-in `gen_random_uuid()` instead of the `uuid-ossp` extension. The validator checks neither column types nor nullability, so tests assert them directly.
- **Embeddings:** OpenAI `text-embedding-3-small`, 1536 dimensions, cosine distance with an HNSW index (`vector_cosine_ops`). `metadata-mode=none` embeds only the chunk text: metadata such as titles and URLs would add noise to the vectors and is returned with each result anyway.
- **Tests:** full-context tests run against one Testcontainers PostgreSQL on the same pinned image, with a deterministic fake embedding model (feature hashing of words), so no test needs an OpenAI key or the network beyond Docker. The container is shared by the whole test JVM and only its connection properties are published to each context; it is not a bean or a `@ServiceConnection`, because Spring Boot closes container beans with their context, which would stop it for every other cached context.

## Options rejected

- **`initialize-schema=true`:** the DDL would be unversioned and run on every startup; it also creates the `hstore` and `uuid-ossp` extensions the project does not need, which may require a superuser outside the local container; and a dimension change would need `remove-existing-vector-store-table`, which drops every stored embedding.
- **Plain JDBC without Spring AI's store:** full control of the SQL, but it gives up `VectorStore`, its filter expressions and the retrieval advisors the RAG step builds on, for code the project would have to write and test itself.

## Planned next (slice B, not implemented yet)

- A small bundled public corpus with a manifest (source URL and license per document) and a NOTICE file.
- Chunking with `TokenTextSplitter` at about 400 tokens per chunk.
- Idempotent ingestion: a content hash per document skips unchanged documents; a changed document has its chunks deleted and re-added in one transaction, using deterministic chunk ids (`UUID.nameUUIDFromBytes(documentId + "#" + chunkIndex)`); documents removed from the manifest are pruned.
- `--ingest` and `--search` command-line options instead of HTTP endpoints, so the unauthenticated API gains no write or bulk-billing surface.

## Consequences

- The application and its full-context tests need PostgreSQL: Docker for local runs and tests, and in CI.
- Changing the embedding model or its dimensions needs a new migration and re-ingesting every document; schema validation stops the startup if the configuration and the table disagree.
- Migrations have no undo: locally, `docker compose down -v` discards the database; anywhere else a mistake is fixed with a new forward migration.
- `CREATE EXTENSION vector` needs a superuser (the local container user is one), `rds_superuser` on Amazon RDS, or the extension created beforehand by an administrator.
- The fake embeddings prove storage and retrieval mechanics (ranking, filters, upserts, deletes), not semantic quality; that needs the real model and the planned evaluation step.
- Spring AI types are used directly; ports and adapters in a hexagonal style are deferred until a second implementation or a real boundary needs them.
