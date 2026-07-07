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
     * Calls the {@code ranked_search} PL/pgSQL function (see V0012 migration), which
     * performs the full 5-signal ranking inside Postgres against the {@code cells}
     * table using pgvector cosine distance and tsvector keyword scoring.
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
                       created_at, valid_from, valid_until,
                       score_semantic, score_keyword, score_recency,
                       score_importance, score_popularity, score_graph_proximity,
                       score_total
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
                    row.get("created_at", OffsetDateTime.class),
                    row.get("valid_from", OffsetDateTime.class),
                    row.get("valid_until", OffsetDateTime.class),
                    doubleValue(row, "score_semantic"),
                    doubleValue(row, "score_keyword"),
                    doubleValue(row, "score_recency"),
                    doubleValue(row, "score_importance"),
                    doubleValue(row, "score_popularity"),
                    doubleValue(row, "score_graph_proximity"),
                    doubleValue(row, "score_total")
            ));
        }
        return rows;
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
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            double scoreSemantic,
            double scoreKeyword,
            double scoreRecency,
            double scoreImportance,
            double scorePopularity,
            double scoreGraphProximity,
            double scoreTotal
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
