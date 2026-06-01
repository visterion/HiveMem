package com.hivemem.queen;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.tools.read.CellFieldSelection;
import com.hivemem.write.WriteToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Business logic behind the inbound Vistierie tool webhooks + completion-webhook ingest. */
@Service
public class QueenWebhookService {

    private static final Logger log = LoggerFactory.getLogger(QueenWebhookService.class);
    private static final Set<String> RELATIONS = Set.of("related_to", "builds_on", "contradicts", "refines");
    private static final AuthPrincipal QUEEN = new AuthPrincipal("queen", AuthRole.AGENT);

    private final QueenProperties props;
    private final QueenRepository repo;
    private final CellReadRepository cells;
    private final CellSearchRepository search;
    private final EmbeddingClient embedding;
    private final WriteToolService writes;

    public QueenWebhookService(QueenProperties props, QueenRepository repo, CellReadRepository cells,
                               CellSearchRepository search, EmbeddingClient embedding, WriteToolService writes) {
        this.props = props;
        this.repo = repo;
        this.cells = cells;
        this.search = search;
        this.embedding = embedding;
        this.writes = writes;
    }

    public Map<String, Object> findIsolatedCells(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), props.getIsolatedBatchLimit());
        List<String> ids = new ArrayList<>();
        for (UUID id : repo.findIsolatedCellIds(limit)) ids.add(id.toString());
        return Map.of("cell_ids", ids);
    }

    public Map<String, Object> readCell(String cellId) {
        Optional<Map<String, Object>> cell = cells.findCell(
                UUID.fromString(cellId),
                CellFieldSelection.forGetCell(List.of("content", "summary", "key_points", "insight")));
        return cell.orElseGet(Map::of);
    }

    public Map<String, Object> searchSimilarCells(String cellId, int requestedLimit) {
        UUID self = UUID.fromString(cellId);
        int limit = Math.min(Math.max(requestedLimit, 1), 10);

        Optional<Map<String, Object>> cell = cells.findCell(self,
                CellFieldSelection.forGetCell(List.of("content", "summary")));
        if (cell.isEmpty()) return Map.of("candidates", List.of());
        String text = String.valueOf(cell.get().getOrDefault("content", cell.get().get("summary")));

        List<Float> emb = embedding.encodeQuery(text);
        List<RankedRow> rows = search.rankedSearch(emb, text, null, null, null, limit + 1,
                0.30, 0.15, 0.15, 0.15, 0.15, 0.10);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (RankedRow r : rows) {
            if (r.id().equals(self)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cell_id", r.id().toString());
            c.put("summary", r.summary());
            c.put("realm", r.realm());
            c.put("score", r.scoreTotal());
            candidates.add(c);
            if (candidates.size() >= limit) break;
        }
        return Map.of("candidates", candidates);
    }

    /** Writes valid, non-duplicate proposals as pending tunnels. Returns count written. */
    public int ingestProposals(List<Map<String, Object>> proposals) {
        if (proposals == null) return 0;
        int written = 0;
        for (Object item : proposals) {
            try {
                if (!(item instanceof Map<?, ?> p)) {
                    log.warn("Queen proposal skipped: not an object: {}", item);
                    continue;
                }
                String relation = String.valueOf(p.get("relation"));
                if (!RELATIONS.contains(relation)) {
                    log.warn("Queen proposal skipped: bad relation '{}'", relation);
                    continue;
                }
                UUID from = UUID.fromString(String.valueOf(p.get("from_cell")));
                UUID to = UUID.fromString(String.valueOf(p.get("to_cell")));
                if (from.equals(to)) continue;
                if (repo.tunnelExists(from, to, relation)) continue;
                String note = p.get("note") == null ? null : String.valueOf(p.get("note"));
                writes.addTunnel(QUEEN, from, to, relation, note, "pending");
                written++;
            } catch (RuntimeException e) {
                log.warn("Queen proposal skipped: invalid payload {}", item, e);
            }
        }
        return written;
    }
}
