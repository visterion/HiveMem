package com.hivemem.hooks;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.search.SearchWeights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HookContextService {

    private static final Logger log = LoggerFactory.getLogger(HookContextService.class);
    private static final int SEARCH_LIMIT = 10;

    private final CellSearchRepository searchRepository;
    private final EmbeddingClient embeddingClient;
    private final SkipHeuristics skipHeuristics;
    private final SessionInjectionCache cache;
    private final ContextFormatter formatter;
    private final HookProperties props;

    private final ConcurrentHashMap<String, AtomicInteger> turnCounters = new ConcurrentHashMap<>();

    public HookContextService(
            CellSearchRepository searchRepository,
            EmbeddingClient embeddingClient,
            SkipHeuristics skipHeuristics,
            SessionInjectionCache cache,
            ContextFormatter formatter,
            HookProperties props
    ) {
        this.searchRepository = searchRepository;
        this.embeddingClient = embeddingClient;
        this.skipHeuristics = skipHeuristics;
        this.cache = cache;
        this.formatter = formatter;
        this.props = props;
    }

    public String contextFor(HookContextRequest req) {
        return contextFor(req, null, null);
    }

    public String contextFor(HookContextRequest req, Double thresholdOverride, Integer maxCellsOverride) {
        if (!props.isEnabled()) return "";
        if (req == null || req.prompt() == null) return "";
        if (skipHeuristics.evaluate(req.prompt()).skip()) return "";

        String sessionKey = req.session_id() == null ? "_" : req.session_id();
        int turn = turnCounters
                .computeIfAbsent(sessionKey, k -> new AtomicInteger())
                .incrementAndGet();

        List<RankedRow> rows;
        try {
            List<Float> queryVector = embeddingClient.encodeQuery(req.prompt());
            SearchWeights w = props.getWeights().toSearchWeights();
            rows = searchRepository.rankedSearch(
                    queryVector, req.prompt(), null, null, null, SEARCH_LIMIT,
                    w.semantic(), w.keyword(), w.recency(),
                    w.importance(), w.popularity(), w.graphProximity());
        } catch (RuntimeException e) {
            log.warn("Hook search failed; returning empty context", e);
            return "";
        }

        double threshold = thresholdOverride != null ? thresholdOverride : props.getRelevanceThreshold();
        int maxCells = maxCellsOverride != null ? maxCellsOverride : props.getMaxCells();
        double minSemantic = props.getMinSemanticScore();
        String projectHint = extractProjectHint(req.cwd());

        Comparator<RankedRow> order = projectHint != null
                ? cwdFirstComparator(projectHint)
                : Comparator.comparingDouble(r -> -r.scoreTotal());

        List<RankedRow> filtered = rows.stream()
                .filter(r -> r.scoreTotal() >= threshold)
                .filter(r -> r.scoreSemantic() >= minSemantic)
                .filter(r -> !cache.recentlyInjected(sessionKey, r.id(), turn))
                .sorted(order)
                .limit(maxCells)
                .toList();

        if (filtered.isEmpty()) return "";
        for (RankedRow r : filtered) {
            cache.recordInjection(sessionKey, r.id(), turn);
        }
        // TODO Task 4: populate refs from findReferencesForCells
        List<CellWithCitation> cells = filtered.stream()
                .map(r -> new CellWithCitation(r, List.of()))
                .toList();
        return formatter.format(cells, turn);
    }

    private String extractProjectHint(String cwd) {
        if (cwd == null || cwd.isBlank()) return null;
        try {
            Path path = Path.of(cwd.trim());
            Path last = path.getFileName();
            return last != null ? last.toString().toLowerCase(Locale.ROOT) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Comparator<RankedRow> cwdFirstComparator(String project) {
        return Comparator.comparingInt((RankedRow r) -> matchesProject(r, project) ? 0 : 1)
                .thenComparingDouble(r -> -r.scoreTotal());
    }

    private boolean matchesProject(RankedRow r, String project) {
        if (r.topic() != null && r.topic().toLowerCase(Locale.ROOT).contains(project)) return true;
        if (r.realm() != null && r.realm().toLowerCase(Locale.ROOT).contains(project)) return true;
        if (r.tags() != null) {
            for (String tag : r.tags()) {
                if (tag != null && tag.toLowerCase(Locale.ROOT).contains(project)) return true;
            }
        }
        return false;
    }
}
