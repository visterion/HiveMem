package com.hivemem.search;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repository for facet-count queries over active cells.
 *
 * <p>Counts are performed over ALL active cells (valid_until IS NULL OR valid_until > now(),
 * matching the {@code active_cells} view), including both committed and pending, so the
 * {@code status} facet reflects the full picture.
 *
 * <p>In addition to cell-column fields (tag, status, realm, year, signal), fields of the
 * form {@code fact:<predicate>} are supported: they count documents grouped by the
 * {@code object} of committed facts with that predicate, joined via {@code source_id}.
 */
@Repository
public class FacetRepository {

    private static final Set<String> ALLOWED_FIELDS = Set.of("tag", "status", "realm", "year", "signal");

    private static final Set<String> ALLOWED_FACT_PREDICATES = Set.of(
            "vendor", "party", "amount_total", "value_per_period",
            "document_date", "due_date", "invoice_number", "contract_number"
    );

    private final DSLContext dslContext;

    public FacetRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    /**
     * Returns aggregate document counts grouped by each requested field.
     *
     * @param realm   optional realm filter
     * @param realmIn optional realm-in filter (matches any of these realms; "none" matches NULL realm)
     * @param signal  optional signal filter
     * @param topic   optional topic filter
     * @param tags    optional tag-overlap filter (any of these tags)
     * @param status  optional status filter
     * @param query   optional full-text query filter
     * @param fields  list of fields to facet on (must be in the allow-list)
     * @param limit   maximum number of buckets per facet
     * @return map from field name to ordered list of {value, count} maps
     */
    public Map<String, List<Map<String, Object>>> facetCounts(
            String realm,
            List<String> realmIn,
            String signal,
            String topic,
            List<String> tags,
            String status,
            String query,
            List<String> fields,
            int limit
    ) {
        for (String f : fields) {
            if (f.startsWith("fact:")) {
                String predicate = f.substring(5);
                if (!ALLOWED_FACT_PREDICATES.contains(predicate)) {
                    throw new IllegalArgumentException("Unknown fact predicate: " + predicate);
                }
            } else if (!ALLOWED_FIELDS.contains(f)) {
                throw new IllegalArgumentException("Unknown facet field: " + f);
            }
        }

        String[] tagsArr = (tags != null && !tags.isEmpty()) ? tags.toArray(new String[0]) : null;
        String[] realmInArr = (realmIn != null && !realmIn.isEmpty()) ? realmIn.toArray(new String[0]) : null;

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();

        for (String field : fields) {
            List<Object> binds = buildBinds(realm, realmInArr, signal, topic, tagsArr, status, query);
            String sql;
            if (field.startsWith("fact:")) {
                String predicate = field.substring(5);
                sql = buildFactFieldSql(predicate, sharedWhere("c."), limit, binds);
            } else {
                sql = buildFieldSql(field, sharedWhere(""), limit, binds);
            }
            org.jooq.Result<org.jooq.Record> rows = dslContext.fetch(sql, binds.toArray());
            List<Map<String, Object>> facetRows = new ArrayList<>();
            for (org.jooq.Record r : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", r.get("value", String.class));
                entry.put("count", r.get("count", Integer.class));
                facetRows.add(entry);
            }
            result.put(field, facetRows);
        }

        return result;
    }

    /**
     * Builds the shared WHERE clause with positional ? parameters, prefixing every cells
     * column reference with {@code prefix} ("" for unaliased queries, "c." for aliased joins).
     * Parameter order per field query: realm x4, realm_in x3, signal x2, topic x2, tags x2, status x2, query x3.
     * The realm sentinel "none" matches NULL realms only (never a literal realm named
     * "none"), so the equality branch is gated on the param NOT being the sentinel.
     * Active predicate matches the {@code active_cells} view semantics.
     */
    private static String sharedWhere(String prefix) {
        return "(" + prefix + "valid_until IS NULL OR " + prefix + "valid_until > now()) " +
                "AND (?::text IS NULL OR (?::text = 'none' AND " + prefix + "realm IS NULL) OR (?::text <> 'none' AND " + prefix + "realm = ?::text)) " +
                "AND (?::text[] IS NULL OR " + prefix + "realm = ANY(array_remove(?::text[], 'none')) OR ('none' = ANY(?::text[]) AND " + prefix + "realm IS NULL)) " +
                "AND (?::text IS NULL OR " + prefix + "signal = ?::text) " +
                "AND (?::text IS NULL OR " + prefix + "topic = ?::text) " +
                "AND (?::text[] IS NULL OR " + prefix + "tags && ?::text[]) " +
                "AND (?::text IS NULL OR " + prefix + "status = ?::text) " +
                "AND (?::text IS NULL OR ?::text = '' OR " + prefix + "tsv @@ plainto_tsquery('simple', ?::text))";
    }

    /**
     * Builds the bind parameter list for the shared WHERE clause.
     * Order: realm×4, realm_in×3, signal×2, topic×2, tags×2, status×2, query×3
     */
    private static List<Object> buildBinds(
            String realm, Object realmInArr, String signal, String topic,
            Object tagsArr, String status, String query
    ) {
        List<Object> b = new ArrayList<>();
        b.add(realm);   b.add(realm);   b.add(realm);   b.add(realm);
        b.add(realmInArr); b.add(realmInArr); b.add(realmInArr);
        b.add(signal);  b.add(signal);
        b.add(topic);   b.add(topic);
        b.add(tagsArr); b.add(tagsArr);
        b.add(status);  b.add(status);
        b.add(query);   b.add(query);   b.add(query);
        return b;
    }

    /**
     * Builds the fact-facet SQL for {@code fact:<predicate>} fields.
     * Joins {@code active_facts} to the filtered cell set via {@code source_id}; documents are
     * counted distinct so multiple same-predicate facts on one cell count that document once.
     * The predicate value is validated against the allow-list before this is called.
     * Parameter order: same shared binds (built with the "c." prefix) + predicate + limit.
     */
    private static String buildFactFieldSql(String predicate, String aliasedWhere, int limit, List<Object> binds) {
        binds.add(predicate);
        binds.add(limit);
        return "SELECT f.\"object\" AS value, count(DISTINCT c.id)::int AS count " +
               "FROM active_facts f " +
               "JOIN cells c ON c.id = f.source_id " +
               "WHERE " + aliasedWhere + " " +
               "AND f.predicate = ? " +
               "GROUP BY f.\"object\" " +
               "ORDER BY count DESC, value " +
               "LIMIT ?";
    }

    /**
     * Builds the per-field SQL and appends the limit bind if needed.
     * The field name is only used to select from a fixed switch — never interpolated.
     */
    private static String buildFieldSql(String field, String sharedWhere, int limit, List<Object> binds) {
        switch (field) {
            case "tag" -> {
                binds.add(limit);
                return "SELECT tag AS value, count(*)::int AS count " +
                       "FROM cells, unnest(tags) AS tag " +
                       "WHERE " + sharedWhere + " " +
                       "GROUP BY tag ORDER BY count DESC, value LIMIT ?";
            }
            case "status" -> {
                return "SELECT status AS value, count(*)::int AS count " +
                       "FROM cells WHERE " + sharedWhere + " " +
                       "GROUP BY status ORDER BY count DESC";
            }
            case "realm" -> {
                return "SELECT realm AS value, count(*)::int AS count " +
                       "FROM cells WHERE " + sharedWhere + " " +
                       "GROUP BY realm ORDER BY count DESC";
            }
            case "signal" -> {
                return "SELECT signal AS value, count(*)::int AS count " +
                       "FROM cells WHERE " + sharedWhere + " AND signal IS NOT NULL " +
                       "GROUP BY signal ORDER BY count DESC";
            }
            case "year" -> {
                return "SELECT to_char(created_at,'YYYY') AS value, count(*)::int AS count " +
                       "FROM cells WHERE " + sharedWhere + " " +
                       "GROUP BY 1 ORDER BY value DESC";
            }
            default -> throw new IllegalArgumentException("Unknown facet field: " + field);
        }
    }
}
