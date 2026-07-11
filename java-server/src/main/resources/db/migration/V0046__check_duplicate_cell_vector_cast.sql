-- V0046: check_duplicate_cell — cast embedding to vector(dim) so the HNSW index is used.
--
-- idx_cells_embedding is an expression index on (embedding::vector(dim)) (see
-- EmbeddingStateRepository.createEmbeddingIndex, the source of truth for the live dimension).
-- The original definition (V0010) compared the bare, untyped `embedding` column against
-- `query_embedding`, which bypasses that expression index entirely and forces a sequential
-- scan of active_cells on every duplicate-check call.
--
-- The embedding dimension is determined at runtime (self-reported by the embedding service,
-- persisted in identity.embedding_dimension) rather than being a fixed literal anywhere in the
-- codebase, so this function cannot read it dynamically without a larger refactor of its call
-- site (WriteToolRepository.checkDuplicateCell, which does not currently thread a dimension
-- parameter through). The live model is paraphrase-multilingual-MiniLM-L12-v2 with
-- embedding_dimension=384, so this migration hardcodes vector(384) to match the current index —
-- this couples the function to that dimension. If the embedding model/dimension ever changes,
-- this function must be re-migrated in lockstep with the HNSW index rebuild
-- (EmbeddingMigrationService), the same way ranked_search is re-rendered per dimension.
CREATE OR REPLACE FUNCTION check_duplicate_cell(
    query_embedding vector, threshold REAL DEFAULT 0.95
)
RETURNS TABLE (id UUID, similarity REAL, summary TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.id, (1 - sub.dist)::REAL AS similarity, sub.summary
    FROM (
        SELECT c.id, c.summary, (c.embedding::vector(384) <=> query_embedding::vector(384)) AS dist
        FROM active_cells c WHERE c.embedding IS NOT NULL
        ORDER BY c.embedding::vector(384) <=> query_embedding::vector(384) LIMIT 20
    ) sub
    WHERE (1 - sub.dist)::REAL > threshold
    ORDER BY sub.dist ASC LIMIT 5;
END;
$$ LANGUAGE plpgsql;
