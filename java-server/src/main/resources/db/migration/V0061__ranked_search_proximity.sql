-- V0061: ranked_search adds a proximity component to its BM25 keyword signal.
--
-- Why: whole-cell BM25 dilutes the evidence in long cells, where the answer to a question sits in
-- a few lines. Among the 100 best BM25 cells, a cell in which the query's rare terms (document
-- frequency / n <= 0.5) stand within 30 token positions of each other is now ranked up.
--
-- What changes: for a query with at least two rare terms and at most 64 distinct lexemes, the
-- lexical score of each of the 100 best BM25 cells is bm25 + 4 * coverage * (best BM25 of the
-- query), where coverage is the idf share of the rare terms inside the cell's best 30-position
-- window (content positions only, position 16,383 and later left out). score_keyword is still the
-- lexical score divided by the best one of the query, in [0, 1]. For any other query the keyword
-- score is exactly the BM25 value of V0060. The signature, the other five signals, the final
-- filter and the order are unchanged. No table, column or index changes.
--
-- This migration is the Flyway fallback copy of
-- java-server/src/main/resources/db/templates/ranked_search.sql.tmpl (the authoritative,
-- runtime-rendered version, with the dimension hard-coded as vector(1024) as in V0057 and V0060).
-- The application re-creates the function from the template at every start, so this file only
-- serves environments that migrate without booting the application.
-- RankedSearchTemplateTest checks that the statement below equals the rendered template.

CREATE OR REPLACE FUNCTION ranked_search(
    query_embedding vector,
    query_text TEXT,
    p_realm TEXT DEFAULT NULL,
    p_signal TEXT DEFAULT NULL,
    p_topic TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 10,
    p_weight_semantic REAL DEFAULT 0.30,
    p_weight_keyword REAL DEFAULT 0.15,
    p_weight_recency REAL DEFAULT 0.15,
    p_weight_importance REAL DEFAULT 0.15,
    p_weight_popularity REAL DEFAULT 0.15,
    p_weight_graph_proximity REAL DEFAULT 0.10,
    p_tags TEXT[] DEFAULT NULL,
    p_status TEXT DEFAULT NULL,
    p_relation_weights JSONB DEFAULT
        '{"builds_on":1.0,"refines":0.8,"related_to":0.6,"contradicts":0.4}'::jsonb,
    p_graph_max_depth INT DEFAULT 2,
    p_realms TEXT[] DEFAULT NULL
)
RETURNS TABLE (
    id UUID, content TEXT, summary TEXT, realm TEXT, signal TEXT, topic TEXT,
    tags TEXT[], importance SMALLINT, key_points TEXT[], insight TEXT,
    created_at TIMESTAMPTZ, valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    score_semantic REAL, score_keyword REAL, score_recency REAL,
    score_importance REAL, score_popularity REAL, score_graph_proximity REAL,
    score_total REAL,
    match_page_from INTEGER, match_page_to INTEGER, match_excerpt TEXT
)
-- hnsw.ef_search is set as a function-level GUC (design §3.6a): LIMIT 400 alone pushes the
-- planner's HNSW startup cost estimate past the seq-scan crossover at the default ef_search=40,
-- so without this the chunk_ann ANN prefilter below silently degrades to a seq scan (measured
-- 53.9 ms vs 4.9 ms). A function-level SET, unlike a session SET, does not depend on the caller.
LANGUAGE SQL STABLE SET hnsw.ef_search = 500 AS $$
    -- Keyword signal: BM25 (k1 = 1.5, b = 0.75) computed at query time from cells.tsv and its GIN
    -- index; no stored statistics. `terms` holds the distinct lexemes of the query, each with its
    -- one-term tsquery (quoted so punctuation inside a lexeme cannot be read as tsquery syntax).
    WITH terms AS (
        SELECT lex,
               to_tsquery('simple', '''' || replace(lex, '''', '''''') || '''') AS tq
        FROM unnest(tsvector_to_array(to_tsvector('simple', coalesce(query_text, '')))) AS lex
    ),
    -- Corpus statistics. n and avgdl, and the document frequencies in `idf`, and the pool in `d`
    -- all use the same seven filter predicates (validity, status, realm, realms, signal, topic,
    -- tags) as the ANN pools, so a cell excluded by a filter never changes the idf of the cells
    -- that are included. Document length is the byte length of summary plus content; it is a
    -- proxy for the token count that needs no stored column.
    st AS (
        SELECT count(*)::float8 AS n,
               avg((octet_length(coalesce(c.summary, '')) + octet_length(coalesce(c.content, '')))::float8) AS avgdl
        FROM cells c
        WHERE (c.valid_until IS NULL OR c.valid_until > now())
          AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
          AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
          AND (p_realms IS NULL
               OR c.realm = ANY(array_remove(p_realms, 'none'))
               OR ('none' = ANY(p_realms) AND c.realm IS NULL))
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
          AND (p_tags IS NULL OR c.tags && p_tags)
    ),
    -- Document frequency per term by one GIN-backed count each; terms found in no document are
    -- dropped. idf = ln(1 + (n - df + 0.5) / (df + 0.5)) is always positive. dfn = df / n is the
    -- share of the admitted cells that contain the term; the proximity window below calls a term
    -- rare when dfn <= 0.5.
    idf AS (
        SELECT t.lex, t.tq, d.df / NULLIF(st.n, 0) AS dfn,
               ln(1 + (st.n - d.df + 0.5) / (d.df + 0.5)) AS idf
        FROM terms t
        CROSS JOIN st
        CROSS JOIN LATERAL (
            SELECT count(*)::float8 AS df
            FROM cells c
            WHERE (c.valid_until IS NULL OR c.valid_until > now())
              AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
              AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
              AND (p_realms IS NULL
                   OR c.realm = ANY(array_remove(p_realms, 'none'))
                   OR ('none' = ANY(p_realms) AND c.realm IS NULL))
              AND (p_signal IS NULL OR c.signal = p_signal)
              AND (p_topic IS NULL OR c.topic = p_topic)
              AND (p_tags IS NULL OR c.tags && p_tags)
              AND c.tsv @@ t.tq
        ) d
        WHERE d.df > 0
    ),
    -- q.tsq is the OR of the surviving terms (NULL when there are none: no query text, only
    -- punctuation, or only unknown terms -- then no document matches and every kw is 0).
    -- q also carries the proximity window's inputs: the rare lexemes, the sum of their idf, and
    -- `wnd`, whether the window applies at all: at least two rare terms (with one, every cell that
    -- holds the term has full coverage, so the window orders nothing) and at most 64 distinct
    -- query lexemes (the row count of `terms`, before terms without a match are dropped). The
    -- window costs time in proportion to the rare-term positions of the 100 candidates, and every
    -- window sums over the idf rows; a whole document passed as the query text would make both
    -- large and must not pay for that. When `wnd` is false the keyword score is plain BM25.
    q AS (
        SELECT to_tsquery('simple', string_agg('''' || replace(lex, '''', '''''') || '''', ' | ')) AS tsq,
               array_agg(lex) AS lexes,
               array_agg(lex) FILTER (WHERE dfn <= 0.5) AS rare,
               COALESCE(sum(idf) FILTER (WHERE dfn <= 0.5), 0) AS rare_idf,
               (count(*) FILTER (WHERE dfn <= 0.5) >= 2
                AND (SELECT count(*) FROM terms) <= 64) AS wnd
        FROM idf
    ),
    -- MATERIALIZED on `d` and `m` is required, not a hint: without it PostgreSQL inlines both
    -- into every aggregate argument of `bm25` and re-evaluates ts_filter/ts_rank_cd per use
    -- (measured 29 s instead of 0.8 s). `v` is the document's tsvector with the query lexemes re-weighted
    -- to A and then filtered to weight A. Summary lexemes already carry weight A, so non-query
    -- summary lexemes survive the filter too; that is harmless because tf is taken per term.
    -- ts_rank_cd(v, term, 0) is then the number of positions of the term -- summary (weight A)
    -- and content (weight B) both count. A tsvector keeps at most 255 positions
    -- per lexeme, so tf is capped there.
    d AS MATERIALIZED (
        SELECT c.id, ts_filter(setweight(c.tsv, 'A', q.lexes), '{a}') AS v,
               (octet_length(coalesce(c.summary, '')) + octet_length(coalesce(c.content, '')))::float8 AS dl
        FROM cells c, q
        WHERE (c.valid_until IS NULL OR c.valid_until > now())
          AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
          AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
          AND (p_realms IS NULL
               OR c.realm = ANY(array_remove(p_realms, 'none'))
               OR ('none' = ANY(p_realms) AND c.realm IS NULL))
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
          AND (p_tags IS NULL OR c.tags && p_tags)
          AND c.tsv @@ q.tsq
    ),
    m AS MATERIALIZED (
        SELECT d.id, d.dl, i.idf, ts_rank_cd(d.v, i.tq, 0)::float8 AS tf
        FROM d JOIN idf i ON d.v @@ i.tq
    ),
    -- BM25 per document: sum over query terms of idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avgdl)).
    -- A zero avgdl (only empty cells) is guarded so the length term degrades to 0, not to NaN.
    bm25 AS (
        SELECT m.id,
               sum(m.idf * m.tf * 2.5 /
                   (m.tf + 1.5 * (0.25 + 0.75 * m.dl / COALESCE(NULLIF(st.avgdl, 0), 1)))) AS score
        FROM m CROSS JOIN st
        GROUP BY m.id
    ),
    -- Proximity window. Among the 100 best BM25 documents, a document whose rare query terms
    -- stand close together is ranked up: its best window of 30 token positions is scored as the
    -- idf of the distinct rare terms inside it, divided by the idf of all rare terms (coverage,
    -- in [0, 1]). `cand` is empty when the window does not apply. MATERIALIZED, like `pp`,
    -- because the planner would otherwise recompute both for every use.
    cand AS MATERIALIZED (
        SELECT b.id, b.score
        FROM bm25 b, q
        WHERE q.wnd
        ORDER BY b.score DESC, b.id
        LIMIT 100
    ),
    -- Positions of the rare terms in the CONTENT only: weight B of tsv. The summary carries
    -- weight A, and the content's positions continue right after the summary's, so summary words would
    -- otherwise count as neighbours of the first content words; BM25's tf still counts them.
    -- The rare lexemes are re-weighted to A, then filtered to A. Position 16,383 is left out:
    -- a tsvector stores every position beyond 16,383 as 16,383, so all late occurrences in a
    -- very long document would appear to stand at the same place and fake proximity. Evidence
    -- beyond position 16,382 of a document therefore gets no bonus.
    pp AS MATERIALIZED (
        SELECT cand.id, u.lexeme, p.pos
        FROM cand
        JOIN cells c ON c.id = cand.id
        CROSS JOIN q
        CROSS JOIN LATERAL unnest(
            ts_filter(setweight(ts_filter(c.tsv, '{b}'), 'A', q.rare), '{a}')) AS u
        CROSS JOIN LATERAL unnest(u.positions) AS p(pos)
        WHERE p.pos < 16383
    ),
    -- Best window per candidate: for every position a of a rare term, the distinct rare terms
    -- found in [a, a + 30), weighted by idf; the maximum over a. A window frame over the sorted
    -- positions of one document collects the lexemes of the 30 positions starting at the current
    -- one (RANGE ... 29 FOLLOWING on the integer position is exactly [a, a + 30), rows at the same
    -- position included). `idf` has one row per lexeme, so `i.lex = ANY(f.lx)` counts every
    -- distinct rare lexeme once. The cost is proportional to the number of rare-term positions of
    -- the 100 candidates (each window is one frame and one sum over the at most 64 idf rows).
    win AS (
        SELECT f.id,
               max((SELECT sum(i.idf) FROM idf i WHERE i.lex = ANY(f.lx))) AS best
        FROM (
            SELECT pp.id,
                   array_agg(pp.lexeme) OVER (PARTITION BY pp.id ORDER BY pp.pos
                                              RANGE BETWEEN CURRENT ROW AND 29 FOLLOWING) AS lx
            FROM pp
        ) f
        GROUP BY f.id
    ),
    -- Lexical score: BM25 plus 4 * coverage * (best BM25 of the query) for the candidates, plain
    -- BM25 for every other matching document. No non-candidate can outrank a candidate, so the
    -- keyword pool below holds the same documents as with BM25 alone; only values and order among
    -- the candidates change.
    lexical AS (
        SELECT b.id,
               b.score + CASE WHEN cand.id IS NOT NULL
                              THEN 4 * COALESCE(win.best, 0) / NULLIF(q.rare_idf, 0)
                                   * (SELECT max(score) FROM bm25)
                              ELSE 0 END AS score
        FROM bm25 b
        CROSS JOIN q
        LEFT JOIN cand ON cand.id = b.id
        LEFT JOIN win ON win.id = b.id
    ),
    -- Keyword pool: the 200 best documents by lexical score, ties by id.
    kw AS (
        SELECT id FROM lexical ORDER BY score DESC, id LIMIT 200
    ),
    -- Normalisation: a document's score divided by the best score of this query, so the best
    -- lexical match of a query is exactly 1.0 and the signal stays in [0, 1]. When the window
    -- applies, a candidate with full coverage lies in [0.8, 1]. A document without coverage
    -- scores its BM25 divided by the best lexical score of the query: about 0.25 at most when
    -- some candidate has full coverage, higher when none does.
    kwmax AS (
        SELECT max(score) AS mx FROM lexical
    ),
    -- Chunk ANN prefilter, deliberately WITHOUT a join to cells (design §3.6a): joining here
    -- forces the planner off the HNSW index (measured 108 ms vs 4.9 ms with the join moved into
    -- `ann` below, where it only ever runs against chunk_best's <=400 rows instead of the full
    -- cell_chunks table). left(ch.content, 301), not 300: CellSearchRepository turns this into
    -- "300 chars + ellipsis" and needs the 301st character to tell "exactly 300" from "longer".
    chunk_ann AS (
        SELECT ch.cell_id, ch.ordinal, ch.page_from, ch.page_to,
               left(ch.content, 301) AS chunk_content,
               (1 - ((ch.embedding::vector(1024)) <=> query_embedding))::REAL AS chunk_sem
        FROM cell_chunks ch
        WHERE query_embedding IS NOT NULL AND ch.embedding IS NOT NULL
        ORDER BY (ch.embedding::vector(1024)) <=> query_embedding
        LIMIT 400
    ),
    -- Best chunk per cell among the 400 ANN candidates above. `, ordinal` tiebreaks a tied
    -- chunk_sem deterministically (lowest ordinal wins) -- without it, which of two equally
    -- scoring chunks supplies match_page_from/_to/_excerpt is arbitrary and can vary run to run.
    chunk_best AS (
        SELECT DISTINCT ON (cell_id) cell_id, page_from, page_to, chunk_content, chunk_sem
        FROM chunk_ann ORDER BY cell_id, chunk_sem DESC, ordinal
    ),
    ann AS (
        -- Both arms are parenthesized: the first arm's ORDER BY/LIMIT would otherwise be parsed
        -- as applying to the whole UNION, which Postgres rejects as a syntax error.
        (
            SELECT c.id
            FROM cells c
            WHERE (c.valid_until IS NULL OR c.valid_until > now())
              AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
              AND c.embedding IS NOT NULL
              AND query_embedding IS NOT NULL
              AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
              AND (p_realms IS NULL
                   OR c.realm = ANY(array_remove(p_realms, 'none'))
                   OR ('none' = ANY(p_realms) AND c.realm IS NULL))
              AND (p_signal IS NULL OR c.signal = p_signal)
              AND (p_topic IS NULL OR c.topic = p_topic)
              AND (p_tags IS NULL OR c.tags && p_tags)
            ORDER BY (c.embedding::vector(1024)) <=> query_embedding
            LIMIT 200
        )

        UNION

        -- The chunk branch of `ann` (design §3.6b): joins chunk_best (<=400 rows, already
        -- ANN-narrowed) to cells so the same seven filter predicates as the cell-vector branch
        -- above apply here too. Without this, a cell reachable only through its chunk could
        -- bypass a realm/signal/topic/tags/status/valid_until filter that would have excluded it
        -- via the cell-vector branch -- §5.3 tests exactly that parity.
        (
            SELECT c.id
            FROM chunk_best cb
            JOIN cells c ON c.id = cb.cell_id
            WHERE (c.valid_until IS NULL OR c.valid_until > now())
              AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
              AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
              AND (p_realms IS NULL
                   OR c.realm = ANY(array_remove(p_realms, 'none'))
                   OR ('none' = ANY(p_realms) AND c.realm IS NULL))
              AND (p_signal IS NULL OR c.signal = p_signal)
              AND (p_topic IS NULL OR c.topic = p_topic)
              AND (p_tags IS NULL OR c.tags && p_tags)
        )
    ),
    candidates AS (
        SELECT id FROM ann
        UNION
        SELECT id FROM kw
    ),
    -- Anchors sort by the same GREATEST(chunk, cell) expression as `scored` and no longer
    -- require c.embedding IS NOT NULL (design §3.6f): a cell reachable only through its chunk
    -- must be eligible to seed graph_proximity_scores, or the graph channel keeps reinforcing
    -- the pre-chunking ranking for exactly the cells chunking was meant to help find.
    anchors AS (
        SELECT c.id
        FROM cells c
        JOIN candidates ca ON ca.id = c.id
        LEFT JOIN chunk_best cb ON cb.cell_id = c.id
        WHERE query_embedding IS NOT NULL
          AND (c.embedding IS NOT NULL OR cb.chunk_sem IS NOT NULL)
        -- `, c.id` tiebreaks a tied GREATEST score deterministically -- anchors previously had a
        -- total ordering (ties broken by the cell-vector distance operator's own stability),
        -- which this explicit rule restores now that two cells can tie exactly on GREATEST.
        ORDER BY GREATEST(
            COALESCE(cb.chunk_sem, 0::REAL),
            CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                 THEN (1 - ((c.embedding::vector(1024)) <=> query_embedding))::REAL
                 ELSE 0::REAL END
        ) DESC, c.id
        LIMIT 25
    ),
    graph AS (
        SELECT cell_id, score
        FROM graph_proximity_scores(
            (SELECT array_agg(id) FROM anchors),
            p_relation_weights,
            p_graph_max_depth
        )
    ),
    scored AS (
        SELECT c.id, c.content, c.summary, c.realm, c.signal, c.topic,
            c.tags, c.importance, c.key_points, c.insight, c.created_at, c.valid_from, c.valid_until,
            -- sem = GREATEST(chunk, cell), never a replacement (design §3.6c): replacing the cell
            -- vector could push a cell below the `sem > 0.3 OR kw > 0` threshold in the final
            -- WHERE and drop it from the result set entirely. GREATEST can only raise the value.
            GREATEST(COALESCE(cb.chunk_sem, 0::REAL), cs.cell_sem) AS sem,
            COALESCE(b.score / NULLIF(kwmax.mx, 0), 0)::REAL AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - c.created_at)) / (90 * 86400))::REAL AS rec,
            (CASE c.importance
                WHEN 5 THEN 1.0 WHEN 4 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 2 THEN 0.4 WHEN 1 THEN 0.2 ELSE 0.6 END)::REAL AS imp,
            LEAST(COALESCE(cp.recent_access_count, 0)::REAL / 25.0, 1.0)::REAL AS pop,
            COALESCE(g.score, 0)::REAL AS gp,
            -- match_* columns (design §3.6d): NULL unless the chunk actually contributed to sem,
            -- i.e. chunk_sem is present and >= the cell vector's own similarity (cs.cell_sem, the
            -- single definition computed once by the LATERAL below instead of being repeated).
            -- At an exact tie the chunk counts as the provider (>=, not >): the value is identical
            -- either way, so reporting the more informative match location is the more useful
            -- reading.
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.page_from ELSE NULL END AS match_page_from,
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.page_to ELSE NULL END AS match_page_to,
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.chunk_content ELSE NULL END AS match_excerpt
        FROM cells c
        CROSS JOIN kwmax
        JOIN candidates ca ON ca.id = c.id
        LEFT JOIN lexical b ON b.id = c.id
        LEFT JOIN cell_popularity cp ON cp.cell_id = c.id
        LEFT JOIN graph g ON g.cell_id = c.id
        LEFT JOIN chunk_best cb ON cb.cell_id = c.id
        CROSS JOIN LATERAL (
            SELECT CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                        THEN (1 - ((c.embedding::vector(1024)) <=> query_embedding))::REAL
                        ELSE 0::REAL END AS cell_sem
        ) cs
    )
    SELECT s.id, s.content, s.summary, s.realm, s.signal, s.topic,
           s.tags, s.importance, s.key_points, s.insight, s.created_at, s.valid_from, s.valid_until,
           s.sem, s.kw, s.rec, s.imp, s.pop, s.gp,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity +
            s.gp  * p_weight_graph_proximity)::REAL AS score_total,
           s.match_page_from, s.match_page_to, s.match_excerpt
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC, s.id ASC LIMIT p_limit;
$$;
