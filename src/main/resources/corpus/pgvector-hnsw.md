# pgvector and HNSW indexes

pgvector is a PostgreSQL extension that adds a `vector` column type and distance operators, so embeddings can live next to ordinary relational data. A column declared as `vector(1536)` stores exactly 1536 single-precision floats; inserting a vector of another size fails. Keeping vectors in PostgreSQL means one backup, one transaction model and one access-control model for both the text and its embeddings.

## Distance operators

pgvector compares vectors with three main operators. `<->` is the Euclidean (L2) distance, `<#>` is the negative inner product and `<=>` is the cosine distance, defined as one minus the cosine similarity. For embeddings that are already normalized to unit length, such as the ones returned by most embedding APIs, cosine distance and inner product produce the same ranking, and cosine is the usual default because it ignores vector length. A similarity search is an `ORDER BY embedding <=> $1 LIMIT k` query: the smallest distance is the closest match.

## Exact versus approximate search

Without an index, PostgreSQL computes the distance to every row. That exact search returns the true nearest neighbours and is perfectly fine for a few thousand rows, but its cost grows linearly with the table. Approximate nearest neighbour (ANN) indexes trade a little recall, the fraction of true neighbours found, for much lower latency on large tables.

pgvector offers two ANN index types. IVFFlat clusters the vectors into lists and only scans the lists closest to the query; it builds quickly but needs data before it is created and its recall drops when the data drifts. HNSW (Hierarchical Navigable Small World) builds a layered graph in which each vector is linked to its nearest neighbours; a search starts at a sparse top layer and greedily descends to denser layers. HNSW builds more slowly and uses more memory, but it can be created on an empty table and usually offers a better speed-to-recall balance.

## Choosing the operator class

An index only helps queries that use the same distance it was built for. An index created with `vector_cosine_ops` serves `<=>` queries; one created with `vector_l2_ops` serves `<->`. Mixing them silently falls back to a sequential scan, which is a common reason for a "slow vector search" that has an index.

## Tuning parameters

HNSW has two build parameters. `m` is the number of links per node (default 16) and `ef_construction` is the size of the candidate list while building (default 64); higher values give better recall at the cost of build time and index size. At query time, `hnsw.ef_search` (default 40) controls how many candidates are explored; raising it improves recall and slows the query. It also caps how many rows a single index scan can return, so a query with a large `LIMIT` or a selective `WHERE` filter may return fewer rows than expected unless `ef_search` is raised or iterative index scans are enabled.

## Filtering

Metadata filters, such as "only chunks of this document", are applied after the approximate scan. A very selective filter can therefore leave few results. Options include a partial index per frequent filter value, a B-tree index on the filtered column so the planner can choose an exact search, or iterative scans, available since pgvector 0.8, which keep scanning the index until enough rows pass the filter.
