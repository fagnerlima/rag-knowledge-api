-- The table Spring AI's PgVectorStore expects (initialize-schema=false, schema-validation=true), which validates
-- only the column names and the vector size at startup. Deliberate differences from the library's own DDL:
-- - metadata is jsonb, not json: the store already writes it as ?::jsonb and filters with metadata::jsonb @@ jsonpath,
--   and jsonb allows a GIN index on metadata later;
-- - NOT NULL columns: every stored chunk has text, metadata and an embedding;
-- - gen_random_uuid(), built in since PostgreSQL 13, instead of the uuid-ossp extension; the application always sends
--   deterministic ids anyway.
-- Schema-qualified because the store validates public.vector_store regardless of the connection's search_path.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE public.vector_store (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content text NOT NULL,
    metadata jsonb NOT NULL,
    -- text-embedding-3-small: changing the model or its dimensions needs a new migration and re-ingestion.
    embedding vector(1536) NOT NULL
);

CREATE INDEX vector_store_embedding_hnsw_idx ON public.vector_store USING hnsw (embedding vector_cosine_ops);
