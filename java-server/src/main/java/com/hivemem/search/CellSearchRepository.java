package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CellSearchRepository {

    private final DSLContext dslContext;

    public CellSearchRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    /**
     * Calls the {@code ranked_search} SQL function, rendered from
     * {@code db/templates/ranked_search.sql.tmpl} at application start, which performs the
     * 6-signal ranking inside Postgres against the {@code cells} table using pgvector cosine
     * distance and a keyword score (BM25 plus a proximity component for the best BM25 cells)
     * normalised to the best lexical match of the query.
     *
     * <p>The function applies a hard filter ({@code semantic > 0.3 OR keyword > 0})
     * and orders deterministically by total score then id.
     */
    public List<RankedRow> rankedSearch(
            List<Float> queryEmbedding,
            String queryText,
            String realm,
            String signal,
            String topic,
            int limit,
            double weightSemantic,
            double weightKeyword,
            double weightRecency,
            double weightImportance,
            double weightPopularity,
            double weightGraphProximity,
            List<String> tags,
            String status,
            List<String> realmIn
    ) {
        Float[] embeddingArray = queryEmbedding == null ? null : queryEmbedding.toArray(Float[]::new);
        String[] tagsArr = (tags == null || tags.isEmpty()) ? null : tags.toArray(String[]::new);
        String[] realmsArr = (realmIn == null || realmIn.isEmpty()) ? null : realmIn.toArray(String[]::new);
        String sql = """
                SELECT id, content, summary, realm, signal, topic, tags, importance,
                       key_points, insight,
                       created_at, valid_from, valid_until,
                       score_semantic, score_keyword, score_recency,
                       score_importance, score_popularity, score_graph_proximity,
                       score_total, match_page_from, match_page_to, match_excerpt
                FROM ranked_search(?::vector, ?, ?, ?, ?, ?,
                                   ?::real, ?::real, ?::real, ?::real, ?::real, ?::real,
                                   ?::text[], ?, p_realms => ?::text[])
                """;

        List<RankedRow> rows = new ArrayList<>();
        for (Record row : dslContext.fetch(
                sql,
                embeddingArray, queryText, realm, signal, topic, limit,
                (float) weightSemantic, (float) weightKeyword, (float) weightRecency,
                (float) weightImportance, (float) weightPopularity,
                (float) weightGraphProximity,
                tagsArr, status, realmsArr
        )) {
            rows.add(new RankedRow(
                    row.get("id", UUID.class),
                    row.get("content", String.class),
                    row.get("summary", String.class),
                    row.get("realm", String.class),
                    row.get("signal", String.class),
                    row.get("topic", String.class),
                    textArray(row, "tags"),
                    row.get("importance", Integer.class),
                    textArray(row, "key_points"),
                    row.get("insight", String.class),
                    row.get("created_at", OffsetDateTime.class),
                    row.get("valid_from", OffsetDateTime.class),
                    row.get("valid_until", OffsetDateTime.class),
                    doubleValue(row, "score_semantic"),
                    doubleValue(row, "score_keyword"),
                    doubleValue(row, "score_recency"),
                    doubleValue(row, "score_importance"),
                    doubleValue(row, "score_popularity"),
                    doubleValue(row, "score_graph_proximity"),
                    doubleValue(row, "score_total"),
                    row.get("match_page_from", Integer.class),
                    row.get("match_page_to", Integer.class),
                    toExcerpt(row.get("match_excerpt", String.class))
            ));
        }
        return rows;
    }

    /**
     * ranked_search returns the raw chunk text truncated at up to 301 characters (design §3.6a):
     * 301, not 300, so "exactly 300 characters long" stays distinguishable from "longer than
     * 300". This is the conversion the SQL function deliberately leaves to Java: 300 chars +
     * "…" when the chunk was longer, the raw text unchanged (no ellipsis) when it was 300 chars
     * or fewer. {@code null} (no chunk matched) passes through unchanged.
     *
     * <p>Postgres's {@code left(content, 301)} counts characters (codepoints); {@code
     * String.length()}/{@code substring} below count UTF-16 code units. For a chunk containing
     * astral-plane characters (surrogate pairs, e.g. emoji) the two can disagree near the
     * boundary — a spurious ellipsis, or a split surrogate pair. Not addressed here: irrelevant
     * for the current corpus, and a codepoint-aware truncator is not worth the complexity until
     * it is.
     */
    private static String toExcerpt(String rawMatchExcerpt) {
        if (rawMatchExcerpt == null) {
            return null;
        }
        if (rawMatchExcerpt.length() > 300) {
            return rawMatchExcerpt.substring(0, 300) + "…";
        }
        return rawMatchExcerpt;
    }

    public record RankedRow(
            UUID id,
            String content,
            String summary,
            String realm,
            String signal,
            String topic,
            List<String> tags,
            Integer importance,
            List<String> keyPoints,
            String insight,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            double scoreSemantic,
            double scoreKeyword,
            double scoreRecency,
            double scoreImportance,
            double scorePopularity,
            double scoreGraphProximity,
            double scoreTotal,
            Integer matchPageFrom,
            Integer matchPageTo,
            String matchExcerpt
    ) {
    }

    /**
     * Filter-only browse path used by {@code search} when {@code query} is blank but a realm/
     * signal/topic/tags filter is present (e.g. the UI's realm drilldown). No embedding call,
     * no ranking — plain newest-first listing over the same filter semantics as
     * {@link #rankedSearch}, minus the scoring columns.
     */
    public List<BrowseRow> browseByFilter(
            String realm,
            String signal,
            String topic,
            int limit,
            List<String> tags,
            String status,
            List<String> realmIn
    ) {
        String[] tagsArr = (tags == null || tags.isEmpty()) ? null : tags.toArray(String[]::new);
        String[] realmsArr = (realmIn == null || realmIn.isEmpty()) ? null : realmIn.toArray(String[]::new);
        String sql = """
                SELECT id, content, summary, realm, signal, topic, tags, importance,
                       key_points, insight, created_at, valid_from, valid_until
                FROM cells
                WHERE (valid_until IS NULL OR valid_until > now())
                  AND (? = 'all' OR status = COALESCE(?, 'committed'))
                  AND (?::text IS NULL OR (?::text = 'none' AND realm IS NULL) OR (?::text <> 'none' AND realm = ?::text))
                  AND (?::text[] IS NULL
                       OR realm = ANY(array_remove(?::text[], 'none'))
                       OR ('none' = ANY(?::text[]) AND realm IS NULL))
                  AND (?::text IS NULL OR signal = ?::text)
                  AND (?::text IS NULL OR topic = ?::text)
                  AND (?::text[] IS NULL OR tags && ?::text[])
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;

        List<BrowseRow> rows = new ArrayList<>();
        for (Record row : dslContext.fetch(
                sql,
                status, status,
                realm, realm, realm, realm,
                realmsArr, realmsArr, realmsArr,
                signal, signal,
                topic, topic,
                tagsArr, tagsArr,
                limit
        )) {
            rows.add(new BrowseRow(
                    row.get("id", UUID.class),
                    row.get("content", String.class),
                    row.get("summary", String.class),
                    row.get("realm", String.class),
                    row.get("signal", String.class),
                    row.get("topic", String.class),
                    textArray(row, "tags"),
                    row.get("importance", Integer.class),
                    textArray(row, "key_points"),
                    row.get("insight", String.class),
                    row.get("created_at", OffsetDateTime.class),
                    row.get("valid_from", OffsetDateTime.class),
                    row.get("valid_until", OffsetDateTime.class)
            ));
        }
        return rows;
    }

    public record BrowseRow(
            UUID id,
            String content,
            String summary,
            String realm,
            String signal,
            String topic,
            List<String> tags,
            Integer importance,
            List<String> keyPoints,
            String insight,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
    }

    public Map<UUID, List<RefRow>> findReferencesForCells(List<UUID> cellIds) {
        if (cellIds == null || cellIds.isEmpty()) return Map.of();
        UUID[] arr = cellIds.toArray(UUID[]::new);
        String sql = """
                SELECT cr.cell_id, r.title, r.url
                FROM cell_references cr
                JOIN references_ r ON r.id = cr.reference_id
                WHERE cr.cell_id = ANY(?::uuid[])
                """;
        Map<UUID, List<RefRow>> result = new HashMap<>();
        for (UUID id : cellIds) result.put(id, new ArrayList<>());
        for (Record row : dslContext.fetch(sql, (Object) arr)) {
            UUID cellId = row.get("cell_id", UUID.class);
            String title = row.get("title", String.class);
            String url = row.get("url", String.class);
            result.get(cellId).add(new RefRow(cellId, title, url));
        }
        return result;
    }

    public record RefRow(UUID cellId, String title, String url) {}

    private static List<String> textArray(Record row, String field) {
        String[] values = row.get(field, String[].class);
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static double doubleValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0.0d : value.doubleValue();
    }
}
