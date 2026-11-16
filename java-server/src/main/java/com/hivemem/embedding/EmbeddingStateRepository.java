package com.hivemem.embedding;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import com.hivemem.search.SearchKeywordProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStateRepository.class);

    private final DSLContext dslContext;
    private final DataSource dataSource;
    /** Session advisory locks are per-connection: the lock is held on this pinned connection
     *  until {@link #releaseAdvisoryLock} — acquiring and releasing on different pooled
     *  connections (the previous DSLContext-based approach) made the unlock a no-op and left
     *  the lock stuck on an idle pooled connection. */
    private Connection lockConnection;

    private final List<String> stopWordConfigs;

    /** Without a keyword setting: the stop-word filter is off. */
    public EmbeddingStateRepository(DSLContext dslContext, DataSource dataSource) {
        this(dslContext, dataSource, new SearchKeywordProperties());
    }

    /** Direct construction (tests): the given settings, no Spring lookup. */
    public EmbeddingStateRepository(DSLContext dslContext, DataSource dataSource,
                                    SearchKeywordProperties keywordProperties) {
        this.dslContext = dslContext;
        this.dataSource = dataSource;
        this.stopWordConfigs = RankedSearchTemplate.normalizeStopWordConfigs(
                keywordProperties.getStopWordConfigs());
    }

    /**
     * Explicitly {@code @Autowired}: the class declares several constructors, and Spring's implicit
     * single-constructor autowiring only applies when exactly one is declared. The settings bean is
     * optional so that slim test contexts that wire only this repository keep working; absent means
     * "off". A malformed configuration name fails here, i.e. at bean creation.
     */
    @Autowired
    public EmbeddingStateRepository(DSLContext dslContext, DataSource dataSource,
                                    ObjectProvider<SearchKeywordProperties> keywordProperties) {
        this(dslContext, dataSource, keywordProperties.getIfAvailable(SearchKeywordProperties::new));
    }

    /** The configured stop-word configuration names, normalised; empty when the filter is off. */
    public List<String> stopWordConfigs() {
        return stopWordConfigs;
    }

    /** Configured names for which the database has no text search configuration, in configured order. */
    public List<String> unknownStopWordConfigs() {
        if (stopWordConfigs.isEmpty()) {
            return List.of();
        }
        return dslContext.fetch(
                        // PostgreSQL has no to_regconfig(); resolve through the catalog the way an
                        // unqualified name in to_tsvector() resolves, i.e. via the search path.
                        "SELECT n FROM unnest(?::text[]) WITH ORDINALITY AS t(n, i) "
                                + "WHERE NOT EXISTS (SELECT 1 FROM pg_ts_config c "
                                + "WHERE c.cfgname = t.n AND pg_ts_config_is_visible(c.oid)) ORDER BY i",
                        (Object) stopWordConfigs.toArray(new String[0]))
                .getValues("n", String.class);
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
                "SELECT count(*) AS cnt FROM cells WHERE content IS NOT NULL");
        return row == null ? 0 : row.get("cnt", Number.class).intValue();
    }

    /**
     * Keyset-paginated batch of cells still needing re-encoding to {@code targetDimension}: id
     * strictly greater than {@code afterId} (null on the first call), and either no embedding yet
     * or an embedding whose dimension doesn't match the target (or {@code forceAll}, which drops
     * that dimension check entirely — see {@code forceAll}'s javadoc). Unlike {@code ORDER BY
     * created_at LIMIT ? OFFSET ?}, this is immune to concurrent {@code UPDATE}s rewriting rows
     * between page fetches: {@code created_at} is non-unique and each batch's write shifts what
     * OFFSET N means, which could silently skip a row and leave it at the old (now-invalid)
     * dimension, breaking the HNSW index cast on the model this method serves. The {@code id > ?}
     * cursor guarantees every row is visited exactly once per full scan regardless of embedding
     * writes elsewhere, and the dimension predicate (when active) makes a restarted (crash-resumed)
     * scan skip rows already fixed.
     *
     * <p>The batch deliberately covers every status, not just {@code committed}: the HNSW index
     * built at the end of the migration is non-partial (covers the whole table), so a pending or
     * rejected row left at the old dimension would break the {@code CREATE INDEX ...
     * ((embedding::vector(newDim)))} cast just as surely as a committed one.
     *
     * @param forceAll when true, drops the {@code embedding IS NULL OR vector_dims(embedding) <>
     *     ?} predicate and selects every row regardless of its current embedding dimension. This is
     *     required when the embedding identity (model string) changes but the dimension does not:
     *     the dimension predicate would otherwise match zero rows, so the migration would report
     *     success having re-encoded nothing. The trade-off: this predicate also doubles as
     *     crash-resume (a restarted scan normally skips rows already fixed), so a forceAll pass is
     *     NOT resumable — a crash mid-pass means the whole pass replays from the start.
     */
    public List<CellRow> fetchCellBatch(UUID afterId, int targetDimension, int batchSize, boolean forceAll) {
        return dslContext.fetch("""
                SELECT id, content, summary, status FROM cells
                WHERE content IS NOT NULL
                  AND (? ::uuid IS NULL OR id > ?)
                  AND (? OR embedding IS NULL OR vector_dims(embedding) <> ?)
                ORDER BY id ASC
                LIMIT ?
                """, afterId, afterId, forceAll, targetDimension, batchSize)
                .map(r -> new CellRow(r.get("id", UUID.class), r.get("content", String.class),
                        r.get("summary", String.class), r.get("status", String.class)));
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

    /** Clears the vector without tagging. For non-committed rows: a surviving
     *  stale-dimension vector breaks the non-partial index build, but the tag would be
     *  inert — SummarizerRepository.findCellsNeedingSummary filters status='committed'. */
    public void clearEmbedding(UUID id) {
        dslContext.execute("UPDATE cells SET embedding = NULL WHERE id = ?", id);
    }

    public int countFacts() {
        Record row = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM facts");
        return row == null ? 0 : row.get("cnt", Number.class).intValue();
    }

    /** Keyset-paginated batch of facts still needing re-encoding to {@code targetDimension}.
     *  See {@link #fetchCellBatch} for why this replaces OFFSET pagination, and for what
     *  {@code forceAll} does and its crash-resume trade-off. */
    public List<FactRow> fetchFactBatch(UUID afterId, int targetDimension, int batchSize, boolean forceAll) {
        return dslContext.fetch("""
                SELECT id, subject, predicate, "object" FROM facts
                WHERE (? ::uuid IS NULL OR id > ?)
                  AND (? OR embedding IS NULL OR vector_dims(embedding) <> ?)
                ORDER BY id ASC
                LIMIT ?
                """, afterId, afterId, forceAll, targetDimension, batchSize)
                .map(r -> new FactRow(r.get("id", UUID.class), r.get("subject", String.class),
                        r.get("predicate", String.class), r.get("object", String.class)));
    }

    public void updateFactEmbedding(UUID factId, List<Float> embedding) {
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute(
                "UPDATE facts SET embedding = ?::vector WHERE id = ?",
                embeddingArray, factId);
    }

    /** Drops every hnsw/ivfflat index on <table>.embedding, whatever it is called.
     *  These are expression indexes (indkey = 0), so the column name lives only in
     *  indexprs — a pg_attribute join finds nothing. Production carries a stale
     *  idx_drawers_embedding from the drawer→cell rename that no migration knows about. */
    public void dropVectorIndexes(String table) {
        List<String> names = dslContext.fetch(
                "SELECT c.relname FROM pg_index i "
              + "JOIN pg_class c ON c.oid = i.indexrelid "
              + "JOIN pg_am a ON a.oid = c.relam "
              + "WHERE a.amname IN ('hnsw','ivfflat') AND i.indrelid = ?::regclass "
              + "  AND (pg_get_expr(i.indexprs, i.indrelid) LIKE '%embedding%' "
              + "       OR i.indexprs IS NULL)",
                table)
            .getValues(0, String.class);
        for (String name : names) {
            log.info("Dropping vector index {} on {}", name, table);
            // Quoted via DSL.name(): relname is the unquoted catalog spelling, and a raw
            // string concat would down-fold a mixed-case name to lowercase, silently miss
            // it (no IF EXISTS to hide behind), and leave the stale index in place.
            dslContext.dropIndex(DSL.name(name)).execute();
        }
    }

    public void dropEmbeddingIndex() {
        dropVectorIndexes("cells");
    }

    public void createEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_cells_embedding " +
                "ON cells USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    public void dropFactsEmbeddingIndex() {
        dropVectorIndexes("facts");
    }

    public void createFactsEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_facts_embedding " +
                "ON facts USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    /** Same pattern as {@link #createEmbeddingIndex}/{@link #createFactsEmbeddingIndex}: called at
     *  all three EmbeddingMigrationService sites (first run, unchanged model, end of reencode) so
     *  {@code cell_chunks} always has a vector index for the active dimension, not just after a
     *  model change. {@code CREATE INDEX IF NOT EXISTS} makes the repeated calls harmless. */
    public void createChunkEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_cell_chunks_embedding " +
                "ON cell_chunks USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    /** Discards every chunk row and clears every cell's {@code chunked_content_md5} marker in the
     *  same operation. Chunks are derived data (design §3.5): on a model change the correct
     *  response is to drop them, not re-encode them in place, so the window "old chunk dimension,
     *  newly rendered ranked_search function" never exists. The sweep rebuilds them.
     *
     *  <p>The marker clear is mandatory, not cosmetic: {@link com.hivemem.chunk.CellChunkRepository}
     *  selects sweep candidates via {@code chunked_content_md5 IS DISTINCT FROM content_md5} — that
     *  marker records which content the sweep has already considered, so cells that deliberately
     *  produce zero chunk rows aren't reselected forever. A model change never touches
     *  {@code cells.content}, so {@code content_md5} is unchanged; leaving the marker in place
     *  would make every already-considered cell permanently invisible to the sweep once its chunk
     *  rows are gone. Clearing every cell's marker (not just chunked ones) is correct per spec —
     *  cells that legitimately produce no chunk rows simply get re-marked on one extra sweep pass.
     *
     *  <p>Uses {@code TRUNCATE} rather than {@code DELETE}: unlike DELETE it doesn't need to
     *  maintain the HNSW index row-by-row or leave dead tuples behind. TRUNCATE only empties the
     *  index, though — it does *not* drop it, so the existing {@code idx_cell_chunks_embedding}
     *  (built for the old dimension) still exists afterward under its fixed name. The separate
     *  {@code dropVectorIndexes("cell_chunks")} call at the {@code EmbeddingMigrationService} call
     *  site is therefore still required on a dimension change: without it, {@link
     *  #createChunkEmbeddingIndex}'s {@code CREATE INDEX IF NOT EXISTS} would silently no-op against
     *  the stale-dimension index instead of creating one for the new dimension. */
    public void discardChunks() {
        dslContext.execute("TRUNCATE cell_chunks");
        dslContext.execute("UPDATE cells SET chunked_content_md5 = NULL WHERE chunked_content_md5 IS NOT NULL");
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
        String createSql = RankedSearchTemplate.render(dimension, stopWordConfigs);
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

    public record CellRow(UUID id, String content, String summary, String status) {
    }

    public record FactRow(UUID id, String subject, String predicate, String object) {
    }
}
