package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.ConfidenceLevel;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.FacetRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.write.AdminToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;

@Service
public class ReadToolService {

    private static final Logger log = LoggerFactory.getLogger(ReadToolService.class);

    private final CellReadRepository cellReadRepository;
    private final KgSearchRepository kgSearchRepository;
    private final CellSearchRepository cellSearchRepository;
    private final EmbeddingClient embeddingClient;
    private final AdminToolService adminToolService;
    private final SearchWeightsProperties searchWeightsProperties;
    private final ConfidenceThresholds confidenceThresholds;
    private final AttachmentRepository attachmentRepository;
    private final FacetRepository facetRepository;

    public ReadToolService(
            CellReadRepository cellReadRepository,
            KgSearchRepository kgSearchRepository,
            CellSearchRepository cellSearchRepository,
            EmbeddingClient embeddingClient,
            AdminToolService adminToolService,
            SearchWeightsProperties searchWeightsProperties,
            ConfidenceThresholds confidenceThresholds,
            AttachmentRepository attachmentRepository,
            FacetRepository facetRepository
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
                        return a;
                    })
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to load attachments for cell {}", cellId, e);
            return List.of();
        }
    }

    public List<Map<String, Object>> traverse(UUID cellId, int maxDepth, String relationFilter) {
        return cellReadRepository.traverse(cellId, maxDepth, relationFilter);
    }

    public List<Map<String, Object>> quickFacts(String entity) {
        return cellReadRepository.quickFacts(entity);
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
