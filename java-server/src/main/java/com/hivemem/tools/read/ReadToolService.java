package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSelector;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.ConfidenceLevel;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.write.AdminToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

@Service
public class ReadToolService {

    private static final Logger log = LoggerFactory.getLogger(ReadToolService.class);

    /** Backstop on the number of edges fetched from the recursive traversal SQL. */
    private static final int HARD_EDGE_LIMIT = 5000;

    private final CellReadRepository cellReadRepository;
    private final KgSearchRepository kgSearchRepository;
    private final CellSearchRepository cellSearchRepository;
    private final EmbeddingClient embeddingClient;
    private final AdminToolService adminToolService;
    private final SearchWeightsProperties searchWeightsProperties;
    private final ConfidenceThresholds confidenceThresholds;
    private final AttachmentRepository attachmentRepository;
    private final FacetRepository facetRepository;
    private final DocumentListRepository documentListRepository;
    private final MediaListRepository mediaListRepository;
    private final CellSelectorRepository cellSelectorRepository;

    public ReadToolService(
            CellReadRepository cellReadRepository,
            KgSearchRepository kgSearchRepository,
            CellSearchRepository cellSearchRepository,
            EmbeddingClient embeddingClient,
            AdminToolService adminToolService,
            SearchWeightsProperties searchWeightsProperties,
            ConfidenceThresholds confidenceThresholds,
            AttachmentRepository attachmentRepository,
            FacetRepository facetRepository,
            DocumentListRepository documentListRepository,
            MediaListRepository mediaListRepository,
            CellSelectorRepository cellSelectorRepository
    ) {
        this.cellReadRepository = cellReadRepository;
        this.kgSearchRepository = kgSearchRepository;
        this.cellSearchRepository = cellSearchRepository;
        this.embeddingClient = embeddingClient;
        this.adminToolService = adminToolService;
        this.searchWeightsProperties = searchWeightsProperties;
        this.confidenceThresholds = confidenceThresholds;
        this.attachmentRepository = attachmentRepository;
        this.facetRepository = facetRepository;
        this.documentListRepository = documentListRepository;
        this.mediaListRepository = mediaListRepository;
        this.cellSelectorRepository = cellSelectorRepository;
    }

    public Map<String, Object> listCellIds(CellSelector selector, int limit, int offset) {
        int clampedLimit = Math.min(Math.max(limit, 1), 1000);
        int clampedOffset = Math.max(offset, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ids", cellSelectorRepository.selectIds(selector, clampedLimit, clampedOffset));
        result.put("total", cellSelectorRepository.countMatches(selector));
        return result;
    }

    public Map<String, Object> status() {
        return cellReadRepository.statusSnapshot();
    }

    public List<Map<String, Object>> listRealms() {
        return cellReadRepository.listRealms();
    }

    public List<Map<String, Object>> listSignals(String realm) {
        return cellReadRepository.listSignals(realm);
    }

    public List<Map<String, Object>> listTopics(String realm, String signal) {
        return cellReadRepository.listTopics(realm, signal);
    }

    public List<Map<String, Object>> listCellsInTopic(String realm, String signal, String topic) {
        return cellReadRepository.listCellsInTopic(realm, signal, topic);
    }

    public List<Map<String, Object>> search(
            String query,
            int limit,
            String realm,
            String signal,
            String topic,
            CellFieldSelection selection,
            double weightSemantic,
            double weightKeyword,
            double weightRecency,
            double weightImportance,
            double weightPopularity,
            double weightGraphProximity,
            List<String> tags,
            String status
    ) {
        List<Float> queryVector = embeddingClient.encodeQuery(query);
        List<CellSearchRepository.RankedRow> rows = cellSearchRepository.rankedSearch(
                queryVector, query, realm, signal, topic, limit,
                weightSemantic, weightKeyword, weightRecency, weightImportance, weightPopularity,
                weightGraphProximity, tags, status
        );
        return rows.stream()
                .map(row -> projectRow(row, selection,
                        ConfidenceLevel.from(row.scoreTotal(), confidenceThresholds)))
                .toList();
    }

    public List<Map<String, Object>> searchKg(String subject, String predicate, String object_, int limit) {
        return kgSearchRepository.search(subject, predicate, object_, limit);
    }

    public List<Map<String, Object>> searchKg(String query, String subject, String predicate, String object_, int limit) {
        if (query != null && !query.isBlank()) {
            try {
                List<Float> vec = embeddingClient.encodeQuery(query);
                if (vec != null) {
                    return kgSearchRepository.semanticSearch(vec, subject, predicate, object_, limit);
                }
            } catch (RuntimeException e) {
                log.warn("search_kg semantic path unavailable, falling back to ILIKE", e);
            }
        }
        return kgSearchRepository.search(subject, predicate, object_, limit);
    }

    public Map<String, Object> getCell(AuthPrincipal principal, UUID cellId) {
        return getCell(principal, cellId, CellFieldSelection.forGetCell(null));
    }

    public Map<String, Object> getCell(AuthPrincipal principal, UUID cellId, CellFieldSelection selection) {
        Optional<Map<String, Object>> cell = cellReadRepository.findCell(cellId, selection);
        if (cell.isEmpty()) {
            return null;
        }
        adminToolService.logAccess(cellId, null, principal.name());
        Map<String, Object> result = new LinkedHashMap<>(cell.get());
        if (selection.includes("attachments")) {
            result.put("attachments", attachmentsForCell(cellId));
        }
        return result;
    }

    private List<Map<String, Object>> attachmentsForCell(UUID cellId) {
        try {
            return attachmentRepository.findByCellId(cellId).stream()
                    .map(row -> {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("id", row.get("id"));
                        a.put("mime_type", row.get("mime_type"));
                        a.put("original_filename", row.get("original_filename"));
                        a.put("size_bytes", row.get("size_bytes"));
                        a.put("page_count", row.get("page_count"));
                        return a;
                    })
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to load attachments for cell {}", cellId, e);
            return List.of();
        }
    }

    public Map<String, Object> traverse(UUID cellId, int maxDepth, String relationFilter, int maxNodes) {
        List<Map<String, Object>> rows = cellReadRepository.traverse(cellId, maxDepth, relationFilter, HARD_EDGE_LIMIT + 1);
        boolean truncated = rows.size() > HARD_EDGE_LIMIT;
        if (truncated) rows = rows.subList(0, HARD_EDGE_LIMIT);
        Set<Object> nodes = new LinkedHashSet<>();
        nodes.add(cellId.toString());
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> edge : rows) {   // rows are ordered by depth
            Object from = edge.get("from_cell");
            Object to = edge.get("to_cell");
            int missing = 0;
            if (!nodes.contains(from)) missing++;
            if (!nodes.contains(to) && !to.equals(from)) missing++;
            if (nodes.size() + missing > maxNodes) {
                // Skip only this edge: later edges between already-admitted nodes
                // (cycles/reconvergence) must still be admitted.
                truncated = true;
                continue;
            }
            nodes.add(from);
            nodes.add(to);
            edges.add(edge);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", edges);
        result.put("node_count", nodes.size());
        result.put("truncated", truncated);
        return result;
    }

    public List<Map<String, Object>> quickFacts(String entity) {
        return cellReadRepository.quickFacts(entity);
    }

    public Map<String, Object> entityOverview(String subject, int limit) {
        List<Map<String, Object>> cells = search(subject, limit, null, null, null,
                CellFieldSelection.forSearch(null),
                0.30d, 0.15d, 0.15d, 0.15d, 0.15d, 0.10d, null, null);
        List<Map<String, Object>> facts = new ArrayList<>(cellReadRepository.quickFacts(subject));
        if (facts.size() < limit) {
            Set<Object> seen = facts.stream().map(f -> f.get("id")).collect(java.util.stream.Collectors.toSet());
            for (Map<String, Object> f : kgSearchRepository.search(subject, null, null, limit)) {
                if (facts.size() >= limit) break;
                if (seen.add(f.get("id"))) facts.add(f);
            }
        } else {
            facts = facts.subList(0, limit);
        }
        List<Map<String, Object>> tunnels = List.of();
        if (!cells.isEmpty()) {
            UUID topCell = UUID.fromString((String) cells.get(0).get("id"));
            tunnels = cellReadRepository.traverse(topCell, 1, null, HARD_EDGE_LIMIT);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cells", cells);
        result.put("facts", facts);
        result.put("tunnels", tunnels);
        return result;
    }

    public List<Map<String, Object>> timeMachine(String subject, OffsetDateTime asOf, OffsetDateTime asOfIngestion, int limit) {
        return cellReadRepository.timeMachine(subject, asOf, asOfIngestion, limit);
    }

    public List<Map<String, Object>> cellHistory(UUID cellId) {
        return cellReadRepository.cellHistory(cellId);
    }

    public List<Map<String, Object>> factHistory(UUID factId) {
        return cellReadRepository.factHistory(factId);
    }

    public List<Map<String, Object>> pendingApprovals() {
        return cellReadRepository.pendingApprovals();
    }

    public List<Map<String, Object>> readingList(String refType, int limit) {
        return cellReadRepository.readingList(refType, limit);
    }

    public List<Map<String, Object>> listAgents() {
        return cellReadRepository.listAgents();
    }

    public List<Map<String, Object>> diaryRead(String agent, int lastN) {
        return cellReadRepository.diaryRead(agent, lastN);
    }

    public List<Map<String, Object>> getBlueprint(String realm) {
        return cellReadRepository.getBlueprint(realm);
    }

    public Map<String, List<Map<String, Object>>> facetCount(
            String realm,
            String signal,
            String topic,
            List<String> tags,
            String status,
            String query,
            List<String> fields,
            int limit
    ) {
        return facetRepository.facetCounts(realm, signal, topic, tags, status, query, fields, limit);
    }

    public List<Map<String, Object>> listDocuments(
            String realm,
            String signal,
            String topic,
            List<String> tags,
            String status,
            String sort,
            int limit,
            int offset
    ) {
        String effectiveRealm;
        if (realm == null || realm.isBlank()) {
            effectiveRealm = "documents";
        } else if (realm.equals("none")) {
            effectiveRealm = null;
        } else {
            effectiveRealm = realm;
        }
        int clampedLimit = Math.min(Math.max(limit, 1), 200);
        int clampedOffset = Math.max(offset, 0);
        return documentListRepository.listDocuments(
                effectiveRealm, signal, topic, tags, status, sort, clampedLimit, clampedOffset);
    }

    public List<Map<String, Object>> listMedia(String realm, String sort, int limit, int offset) {
        String effectiveRealm = (realm == null || realm.isBlank()) ? null : realm;
        int clampedLimit = Math.min(Math.max(limit, 1), 500);
        int clampedOffset = Math.max(offset, 0);
        return mediaListRepository.listMedia(effectiveRealm, sort, clampedLimit, clampedOffset);
    }

    public Map<String, Object> wakeUp() {
        return cellReadRepository.wakeUp();
    }

    public Map<String, Object> streamSnapshot(int cellLimit, int tunnelLimit) {
        return cellReadRepository.streamSnapshot(cellLimit, tunnelLimit);
    }

    private static Map<String, Object> projectRow(
            CellSearchRepository.RankedRow row,
            CellFieldSelection selection,
            ConfidenceLevel confidenceLevel
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", row.id().toString());
        values.put("realm", row.realm());
        values.put("signal", row.signal());
        values.put("topic", row.topic());
        values.put("content", row.content());
        values.put("summary", row.summary());
        values.put("tags", row.tags());
        values.put("importance", row.importance());
        values.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
        values.put("valid_from", row.validFrom() == null ? null : row.validFrom().toString());
        values.put("valid_until", row.validUntil() == null ? null : row.validUntil().toString());
        Map<String, Object> projected = new LinkedHashMap<>(selection.project(values));
        projected.put("score_semantic", rounded(row.scoreSemantic()));
        projected.put("score_keyword", rounded(row.scoreKeyword()));
        projected.put("score_recency", rounded(row.scoreRecency()));
        projected.put("score_importance", rounded(row.scoreImportance()));
        projected.put("score_popularity", rounded(row.scorePopularity()));
        projected.put("score_total", rounded(row.scoreTotal()));
        projected.put("confidence_level", confidenceLevel.name());
        return projected;
    }

    private static double rounded(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
