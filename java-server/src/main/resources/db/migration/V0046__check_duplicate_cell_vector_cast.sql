-- V0046: check_duplicate_cell — cast embedding to vector(dim) so the HNSW index is used.
--
-- idx_cells_embedding is an expression index on (embedding::vector(dim)) (see
-- EmbeddingStateRepository.createEmbeddingIndex, the source of truth for the live dimension).
-- The original definition (V0010) compared the bare, untyped `embedding` column against
-- `query_embedding`, which bypasses that expression index entirely and forces a sequential
-- scan of active_cells on every duplicate-check call.
--
-- The embedding dimension is determined at runtime (self-reported by the embedding service) and
-- is NOT a fixed literal anywhere in the codebase — hardcoding e.g. vector(384) here would only
-- match the specific model in production today (paraphrase-multilingual-MiniLM-L12-v2) and would
-- silently stop using the index (or error outright on a dimension mismatch) the moment the model
-- changes, or in any environment running a different-dimension embedding stub (test suites do).
-- Instead, derive the dimension from the query vector itself via vector_dims(): for a caller using
-- the CURRENT model this matches the live index dimension. This is narrower than an absolute
-- guarantee, though: a reencode does NOT bulk-NULL every embedding up front — it overwrites
-- embeddings in 100-row batches (see EmbeddingStateRepository.fetchCellBatch /
-- EmbeddingMigrationService), so old- and new-dimension vectors transiently coexist while it
-- runs, and the HNSW index itself is dropped for that whole window regardless. A caller racing
-- that window either gets a query vector in the new dimension (matching only the already-migrated
-- rows) or the old one (matching only the not-yet-migrated rows) — never a crash, just a
-- self-healing narrower/wider match until the reencode finishes.
-- The cast is applied via dynamic SQL (EXECUTE) so the interpolated dimension literal lines up
-- textually with the expression index, the same way KgSearchRepository.semanticSearch builds its
-- SQL string with the dimension inlined as literal text.
CREATE OR REPLACE FUNCTION check_duplicate_cell(
    query_embedding vector, threshold REAL DEFAULT 0.95
)
RETURNS TABLE (id UUID, similarity REAL, summary TEXT) AS $$
DECLARE
    dim INTEGER := vector_dims(query_embedding);
BEGIN
    RETURN QUERY EXECUTE format($f$
        SELECT sub.id, (1 - sub.dist)::REAL AS similarity, sub.summary
        FROM (
            SELECT c.id, c.summary, (c.embedding::vector(%1$s) <=> $1::vector(%1$s)) AS dist
            FROM active_cells c WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding::vector(%1$s) <=> $1::vector(%1$s) LIMIT 20
        ) sub
        WHERE (1 - sub.dist)::REAL > $2
        ORDER BY sub.dist ASC LIMIT 5
    $f$, dim)
    USING query_embedding, threshold;
END;
$$ LANGUAGE plpgsql;
