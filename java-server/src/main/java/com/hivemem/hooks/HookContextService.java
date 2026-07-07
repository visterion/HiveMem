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
import java.util.Map;
import java.util.UUID;
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

    public ContextResult contextFor(HookContextRequest req) {
        return contextFor(req, null, null);
    }

    public ContextResult contextFor(HookContextRequest req, Double thresholdOverride, Integer maxCellsOverride) {
        if (!props.isEnabled()) return ContextResult.empty();
        if (req == null || req.prompt() == null) return ContextResult.empty();
        if (skipHeuristics.evaluate(req.prompt()).skip()) return ContextResult.empty();

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
                    w.importance(), w.popularity(), w.graphProximity(),
                    null, null, null);
        } catch (RuntimeException e) {
            log.warn("Hook search failed; returning empty context", e);
            return ContextResult.empty();
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

        if (filtered.isEmpty()) return ContextResult.empty();

        for (RankedRow r : filtered) {
            cache.recordInjection(sessionKey, r.id(), turn);
        }

        List<UUID> cellIds = filtered.stream().map(RankedRow::id).toList();
        Map<UUID, List<CellSearchRepository.RefRow>> refMapRaw;
        try {
            refMapRaw = searchRepository.findReferencesForCells(cellIds);
        } catch (RuntimeException e) {
            log.warn("Reference lookup failed; proceeding without citations", e);
            refMapRaw = Map.of();
        }
        final Map<UUID, List<CellSearchRepository.RefRow>> refMap = refMapRaw;

        List<CellWithCitation> enriched = filtered.stream()
                .map(r -> new CellWithCitation(r,
                        refMap.getOrDefault(r.id(), List.of()).stream()
                                .map(ref -> new ReferenceInfo(ref.cellId(), ref.title(), ref.url()))
                                .toList()))
                .toList();

        List<SourceAttribution> attributions = enriched.stream()
                .map(c -> {
                    RankedRow r = c.row();
                    Integer year = r.validFrom() != null ? r.validFrom().getYear() : null;
                    if (!c.refs().isEmpty()) {
                        ReferenceInfo ref = c.refs().get(0);
                        return new SourceAttribution(r.id(), r.realm(), r.topic(),
                                year, ref.title(), ref.url());
                    }
                    return new SourceAttribution(r.id(), r.realm(), r.topic(), year, null, null);
                })
                .toList();

        String formatted = formatter.format(enriched, turn);
        return new ContextResult(formatted, attributions);
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
