package com.hivemem.admin;

import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** One-time repair that restores event times replaced by revisions (dry run and real run). */
@Service
public class EventTimeRepairService {

    private static final Logger log = LoggerFactory.getLogger(EventTimeRepairService.class);
    static final String REPAIR_AGENT = "system-repair";

    private final EventTimeRepairRepository repository;
    private final OpLogWriter opLogWriter;
    private final PushDispatcher pushDispatcher;

    public EventTimeRepairService(EventTimeRepairRepository repository, OpLogWriter opLogWriter,
                                  PushDispatcher pushDispatcher) {
        this.repository = repository;
        this.opLogWriter = opLogWriter;
        this.pushDispatcher = pushDispatcher;
    }

    /** Dry run: reports what a real run would change and writes nothing. */
    public Map<String, Object> preview(int limit) {
        List<EventTimeCandidate> candidates = repository.findCandidates();
        Map<String, Integer> byStatus = new TreeMap<>();
        int over1h = 0;
        int over1d = 0;
        int over30d = 0;
        int over365d = 0;
        int later = 0;
        for (EventTimeCandidate c : candidates) {
            byStatus.merge(c.status(), 1, Integer::sum);
            Duration shift = Duration.between(c.newValidFrom(), c.oldValidFrom());
            if (shift.compareTo(Duration.ofHours(1)) > 0) over1h++;
            if (shift.compareTo(Duration.ofDays(1)) > 0) over1d++;
            if (shift.compareTo(Duration.ofDays(30)) > 0) over30d++;
            if (shift.compareTo(Duration.ofDays(365)) > 0) over365d++;
            if (c.newValidFrom().isAfter(c.oldValidFrom())) later++;
        }
        Map<String, Object> shift = new LinkedHashMap<>();
        shift.put("over_1_hour", over1h);
        shift.put("over_1_day", over1d);
        shift.put("over_30_days", over30d);
        shift.put("over_365_days", over365d);
        shift.put("event_time_later", later);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", true);
        body.put("candidates", candidates.size());
        body.put("by_status", new LinkedHashMap<>(byStatus));
        body.put("shift", shift);
        body.put("cells", describe(candidates.stream().limit(limit).toList()));
        return body;
    }

    /**
     * Real run: restores the first {@code limit} candidates. Each cell is updated and logged in its
     * own transaction, so a failure on one cell does not undo cells already repaired. A cell that
     * changed since the query is skipped and logged nowhere.
     */
    public Map<String, Object> repair(int limit) {
        List<EventTimeCandidate> batch = repository.findCandidates().stream().limit(limit).toList();
        List<EventTimeCandidate> updatedCells = new ArrayList<>();
        int skipped = 0;
        for (EventTimeCandidate c : batch) {
            UUID opId = repository.inTransaction(() -> repairOne(c));
            if (opId == null) {
                skipped++;
                continue;
            }
            updatedCells.add(c);
            // Dispatch after the commit: the op must be visible to the push before it is sent.
            pushDispatcher.dispatch(opId);
        }
        log.info("event-time repair: updated={} skipped={}", updatedCells.size(), skipped);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", false);
        body.put("updated", updatedCells.size());
        body.put("skipped", skipped);
        body.put("remaining", repository.findCandidates().size());
        body.put("cells", describe(updatedCells));
        return body;
    }

    /** Returns the op id, or null when the row no longer matched. */
    private UUID repairOne(EventTimeCandidate c) {
        if (repository.compareAndSetValidFrom(c.cellId(), c.oldValidFrom(), c.newValidFrom()) == 0) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cell_id", c.cellId().toString());
        payload.put("document_type", null);
        payload.put("topic", null);
        payload.put("valid_from", c.newValidFrom().toString());
        payload.put("add_tags", List.of());
        payload.put("agent_id", REPAIR_AGENT);
        return opLogWriter.append("update_cell_meta", payload);
    }

    private static List<Map<String, Object>> describe(List<EventTimeCandidate> cells) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EventTimeCandidate c : cells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cell_id", c.cellId().toString());
            m.put("old_valid_from", c.oldValidFrom().toString());
            m.put("new_valid_from", c.newValidFrom().toString());
            out.add(m);
        }
        return out;
    }
}
