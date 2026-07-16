package com.hivemem.queen;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fires an on-demand inbox-archivist run when a cell's enrichment settles. Idempotent by design:
 * the agent surveys the whole inbox per run, so a single trigger covers a burst of uploads. A
 * per-instance, thread-safe debounce collapses concurrent AFTER_COMMIT enrichment callbacks into
 * at most one trigger per window. Never throws -- a failed trigger must not break enrichment; the
 * safety-net cron still catches anything missed.
 *
 * <p>Note: the agent name is the literal {@code "inbox-archivist"} for now;
 * {@code AgentDefinitions.ARCHIVIST_NAME} does not exist yet (added in Task 10, which will unify
 * this call site to the constant).
 */
@Component
public class ArchivistTrigger {

    private static final Logger log = LoggerFactory.getLogger(ArchivistTrigger.class);

    private final DSLContext db;
    private final VistierieAgentClient client;
    private final QueenProperties props;
    private final AtomicLong lastFiredEpochMs = new AtomicLong(0);

    public ArchivistTrigger(DSLContext db, VistierieAgentClient client, QueenProperties props) {
        this.db = db;
        this.client = client;
        this.props = props;
    }

    public void maybeTrigger(UUID cellId) {
        try {
            if (!props.isEnabled() || cellId == null) return;
            if (!isReadyInboxCell(cellId)) return;
            if (!claimDebounceWindow()) return;
            client.triggerRun("inbox-archivist", Map.of());
        } catch (RuntimeException e) {
            log.warn("Archivist trigger failed for cell {} (cron will still cover it): {}", cellId, e.toString());
        }
    }

    /** Live inbox cell whose enrichment is settled -- mirrors QueenRepository.findInboxCellIds. */
    private boolean isReadyInboxCell(UUID cellId) {
        Record row = db.fetchOne("""
                SELECT
                  (realm = 'inbox') AS in_inbox,
                  NOT ('archivist_skipped' = ANY(tags)) AS not_skipped,
                  (NOT (tags && ARRAY['vision_pending','kroki_pending','needs_summary']::text[])
                        AND (NOT ('ocr_pending' = ANY(tags)) OR 'ocr_failed_permanent' = ANY(tags))) AS settled
                FROM active_cells
                WHERE id = ?
                """, cellId);
        return row != null
                && Boolean.TRUE.equals(row.get("in_inbox", Boolean.class))
                && Boolean.TRUE.equals(row.get("not_skipped", Boolean.class))
                && Boolean.TRUE.equals(row.get("settled", Boolean.class));
    }

    /** Returns true iff this call wins the debounce window (CAS on the last-fired timestamp). */
    private boolean claimDebounceWindow() {
        long windowMs = Math.max(0, props.getArchivistDebounceSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        while (true) {
            long last = lastFiredEpochMs.get();
            if (now - last < windowMs) return false;
            if (lastFiredEpochMs.compareAndSet(last, now)) return true;
        }
    }
}
