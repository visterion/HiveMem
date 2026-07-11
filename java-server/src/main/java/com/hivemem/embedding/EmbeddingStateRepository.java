package com.hivemem.embedding;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EmbeddingStateRepository {

    private final DSLContext dslContext;
    private final DataSource dataSource;
    /** Session advisory locks are per-connection: the lock is held on this pinned connection
     *  until {@link #releaseAdvisoryLock} — acquiring and releasing on different pooled
     *  connections (the previous DSLContext-based approach) made the unlock a no-op and left
     *  the lock stuck on an idle pooled connection. */
    private Connection lockConnection;

    public EmbeddingStateRepository(DSLContext dslContext, DataSource dataSource) {
        this.dslContext = dslContext;
        this.dataSource = dataSource;
    }

    public Optional<EmbeddingInfo> loadStoredInfo() {
        Record modelRow = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "embedding_model");
        Record dimRow = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "embedding_dimension");
        if (modelRow == null || dimRow == null) {
            return Optional.empty();
        }
        String model = modelRow.get("content", String.class);
        int dimension = Integer.parseInt(dimRow.get("content", String.class));
        return Optional.of(new EmbeddingInfo(model, dimension));
    }

    public void saveInfo(EmbeddingInfo info) {
        upsert("embedding_model", info.model());
        upsert("embedding_dimension", String.valueOf(info.dimension()));
    }

    public void saveProgress(int done, int total) {
        upsert("reencoding_progress", done + "/" + total);
    }

    public Optional<String> loadProgress() {
        Record row = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "reencoding_progress");
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(row.get("content", String.class));
    }

    public void clearProgress() {
        dslContext.execute("DELETE FROM identity WHERE key = ?", "reencoding_progress");
    }

    public int countCellsWithContent() {
        Record row = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM cells WHERE content IS NOT NULL AND status = 'committed'");
        return row == null ? 0 : row.get("cnt", Number.class).intValue();
    }

    /**
     * Keyset-paginated batch of cells still needing re-encoding to {@code targetDimension}: id
     * strictly greater than {@code afterId} (null on the first call), and either no embedding yet
     * or an embedding whose dimension doesn't match the target. Unlike {@code ORDER BY created_at
     * LIMIT ? OFFSET ?}, this is immune to concurrent {@code UPDATE}s rewriting rows between page
     * fetches: {@code created_at} is non-unique and each batch's write shifts what OFFSET N means,
     * which could silently skip a row and leave it at the old (now-invalid) dimension, breaking
     * the HNSW index cast on the model this method serves. The {@code id > ?} cursor guarantees
     * every row is visited exactly once per full scan regardless of embedding writes elsewhere,
     * and the dimension predicate makes a restarted (crash-resumed) scan skip rows already fixed.
     */
    public List<CellRow> fetchCellBatch(UUID afterId, int targetDimension, int batchSize) {
        return dslContext.fetch("""
                SELECT id, content, summary FROM cells
                WHERE content IS NOT NULL AND status = 'committed'
                  AND (? ::uuid IS NULL OR id > ?)
                  AND (embedding IS NULL OR vector_dims(embedding) <> ?)
                ORDER BY id ASC
                LIMIT ?
                """, afterId, afterId, targetDimension, batchSize)
                .map(r -> new CellRow(r.get("id", UUID.class), r.get("content", String.class),
                        r.get("summary", String.class)));
    }

    public void updateEmbedding(UUID cellId, List<Float> embedding) {
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute(
                "UPDATE cells SET embedding = ?::vector WHERE id = ?",
                embeddingArray, cellId);
    }

    /**
     * NULL the embedding (an old-model vector must not survive a dimension change — it would
     * break the new HNSW index cast) and tag needs_summary so the summarizer refills it.
     */
    public void clearEmbeddingAndTagNeedsSummary(UUID cellId) {
        dslContext.execute(
                "UPDATE cells SET embedding = NULL, tags = "
                + "CASE WHEN 'needs_summary' = ANY(COALESCE(tags, '{}'::text[])) THEN tags "
                + "ELSE array_append(COALESCE(tags, '{}'::text[]), 'needs_summary') END "
                + "WHERE id = ?", cellId);
    }

    public int countFactsCommitted() {
        Record row = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM facts WHERE status = 'committed'");
        return row == null ? 0 : row.get("cnt", Number.class).intValue();
    }

    /** Keyset-paginated batch of facts still needing re-encoding to {@code targetDimension}.
     *  See {@link #fetchCellBatch} for why this replaces OFFSET pagination. */
    public List<FactRow> fetchFactBatch(UUID afterId, int targetDimension, int batchSize) {
        return dslContext.fetch("""
                SELECT id, subject, predicate, "object" FROM facts
                WHERE status = 'committed'
                  AND (? ::uuid IS NULL OR id > ?)
                  AND (embedding IS NULL OR vector_dims(embedding) <> ?)
                ORDER BY id ASC
                LIMIT ?
                """, afterId, afterId, targetDimension, batchSize)
                .map(r -> new FactRow(r.get("id", UUID.class), r.get("subject", String.class),
                        r.get("predicate", String.class), r.get("object", String.class)));
    }

    public void updateFactEmbedding(UUID factId, List<Float> embedding) {
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute(
                "UPDATE facts SET embedding = ?::vector WHERE id = ?",
                embeddingArray, factId);
    }

    public void dropEmbeddingIndex() {
        dslContext.execute("DROP INDEX IF EXISTS idx_cells_embedding");
    }

    public void createEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_cells_embedding " +
                "ON cells USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    public void dropFactsEmbeddingIndex() {
        dslContext.execute("DROP INDEX IF EXISTS idx_facts_embedding");
    }

    public void createFactsEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_facts_embedding " +
                "ON facts USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    public void replaceRankedSearchFunction(int dimension) {
        // Adding a parameter to ranked_search via CREATE OR REPLACE creates a new
        // overload rather than replacing the existing function signature, which would
        // make the old positional-arg call sites ambiguous. Drop all existing
        // overloads first so only the freshly rendered signature remains.
        String dropSql = """
                DO $do$
                DECLARE r RECORD;
                BEGIN
                    FOR r IN SELECT oid::regprocedure AS sig FROM pg_proc
                             WHERE proname = 'ranked_search' AND pronamespace = 'public'::regnamespace LOOP
                        EXECUTE 'DROP FUNCTION ' || r.sig;
                    END LOOP;
                END
                $do$
                """;
        String createSql = RankedSearchTemplate.render(dimension);
        // Drop and create must be atomic: a crash between the two statements would
        // otherwise leave the database without ranked_search until the next boot.
        dslContext.transaction(cfg -> {
            DSL.using(cfg).execute(dropSql);
            DSL.using(cfg).execute(createSql);
        });
    }

    /**
     * Acquire the reencoding advisory lock on a dedicated connection that stays pinned (checked
     * out of the pool) until {@link #releaseAdvisoryLock}, so acquire and release happen on the
     * SAME session. Returns false when another instance holds the lock (or this one already does).
     */
    public synchronized boolean tryAdvisoryLock(long lockId) {
        if (lockConnection != null) {
            return false;
        }
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            boolean acquired = false;
            try (PreparedStatement st = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                st.setLong(1, lockId);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        acquired = rs.getBoolean(1);
                    }
                }
            }
            if (acquired) {
                lockConnection = conn;
                return true;
            }
            conn.close();
            return false;
        } catch (SQLException e) {
            closeQuietly(conn);
            throw new IllegalStateException("Failed to acquire advisory lock " + lockId, e);
        }
    }

    /** Release the lock on the pinned connection, then return the connection to the pool. */
    public synchronized void releaseAdvisoryLock(long lockId) {
        if (lockConnection == null) {
            return;
        }
        try (Connection conn = lockConnection;
             PreparedStatement st = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            st.setLong(1, lockId);
            st.execute();
        } catch (SQLException e) {
            // Best effort: a broken connection is evicted by the pool, which also drops the
            // session-level lock on the server side.
        } finally {
            lockConnection = null;
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // nothing sensible to do
        }
    }

    private void upsert(String key, String content) {
        int tokenCount = content.length() / 4;
        dslContext.execute("""
                INSERT INTO identity (key, content, token_count, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    updated_at = now()
                """, key, content, tokenCount);
    }

    public record CellRow(UUID id, String content, String summary) {
    }

    public record FactRow(UUID id, String subject, String predicate, String object) {
    }
}
