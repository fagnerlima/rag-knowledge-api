# Changelog

All notable changes to this project are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `POST /api/v1/answers` answers a technical question with OpenAI using a strict JSON Schema for the reply (`answer`, `confidence`, `sources`).
- Validation and model failures return RFC 9457 Problem Details (`400` and `502`).
- Health endpoint at `/actuator/health`.
- The server listens only on `127.0.0.1` by default (`SERVER_ADDRESS` overrides it).
- The application refuses to start when `OPENAI_API_KEY` is not set, instead of answering every question with `502`.
- Local PostgreSQL with pgvector through `docker compose up -d` (pinned image, listening only on `127.0.0.1:5433`).
- Database schema for document embeddings, created and versioned by Flyway at startup.
- Embeddings with OpenAI `text-embedding-3-small` (1536 dimensions).
- A bundled corpus of five original technical notes (pgvector and HNSW, PostgreSQL MVCC, Kubernetes Pods, Twelve-Factor, Spring Boot configuration).
- `--ingest` indexes the corpus in chunks of about 400 tokens; re-running it skips unchanged notes, replaces changed ones and removes deleted ones.
- `--search=<query>` prints the most similar chunks with their score and source.

### Changed

- The application now needs PostgreSQL with pgvector to start; it connects with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` (defaults match `compose.yaml`).
- `/actuator/health` also checks the database and reports `DOWN` when it is unreachable.
- Running the tests now requires Docker; they still need no OpenAI key.
