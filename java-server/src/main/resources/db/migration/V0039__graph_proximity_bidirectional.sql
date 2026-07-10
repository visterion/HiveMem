-- Graph proximity walked tunnels in the from->to direction only, so the 6th
-- search signal depended on edge creation direction. Recreate the function to
-- walk both directions (mirroring traverse and the disconnected check).

CREATE OR REPLACE FUNCTION graph_proximity_scores(
    anchors UUID[],
    relation_weights JSONB,
    max_depth INT DEFAULT 2
)
RETURNS TABLE (cell_id UUID, score REAL)
LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE walk(cell_id, depth, path_score) AS (
        SELECT a, 0, 1.0::REAL
        FROM unnest(anchors) AS a
        UNION ALL
        SELECT e.neighbor,
               w.depth + 1,
               (w.path_score
                 * COALESCE((relation_weights ->> e.relation)::REAL, 0.0::REAL)
                 * (1.0::REAL / (w.depth + 1)))::REAL
        FROM walk w
        JOIN (
            SELECT from_cell AS node, to_cell AS neighbor, relation
            FROM tunnels
            WHERE status = 'committed'
              AND (valid_until IS NULL OR valid_until > now())
            UNION ALL
            SELECT to_cell AS node, from_cell AS neighbor, relation
            FROM tunnels
            WHERE status = 'committed'
              AND (valid_until IS NULL OR valid_until > now())
        ) e ON e.node = w.cell_id
        WHERE w.depth < max_depth
    )
    SELECT cell_id, MAX(path_score)::REAL AS score
    FROM walk
    WHERE depth > 0                          -- exclude anchors
      AND NOT (cell_id = ANY(anchors))       -- never boost an anchor
    GROUP BY cell_id;
$$;
