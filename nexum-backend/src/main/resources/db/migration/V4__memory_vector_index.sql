-- Goal-prefixed vector index.
--
-- The prefix column is the important part. Indexing (goal_id, embedding) means
-- CockroachDB narrows to the goal BEFORE ranking by vector similarity, rather
-- than searching every memory in the cluster and filtering afterwards.
--
-- That is Nexum's access model enforced by the storage engine, not just by
-- application code: scope first, similarity second. It is also the honest
-- answer to "why does this need CockroachDB and not a bolt-on vector store" -
-- the relational scope predicate and the vector ranking are the same index.

CREATE VECTOR INDEX IF NOT EXISTS memories_goal_embedding_idx
    ON memories (goal_id, embedding vector_cosine_ops);
