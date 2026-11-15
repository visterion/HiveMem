package com.hivemem.admin;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Finds current cell rows whose event time (valid_from) was overwritten by a revision made before
 * revisions started to keep it, and restores it with a compare-and-set update.
 */
@Repository
public class EventTimeRepairRepository {

    /**
     * Walks every lineage from its root. The event time of a row is the root's valid_from, carried
     * down through every row whose valid_from was only the revision time (equal to its own
     * created_at) or an inherited copy of its parent's; a row that carries a different value was
     * dated on purpose and starts a new event time. Only current rows that differ are candidates.
     */
    public static final String CANDIDATE_SQL = """
            WITH RECURSIVE lineage AS (
                SELECT id, parent_id, valid_from, valid_until, created_at, status,
                       valid_from AS event_time, 0 AS depth
                FROM cells
                WHERE parent_id IS NULL
                UNION ALL
                SELECT c.id, c.parent_id, c.valid_from, c.valid_until, c.created_at, c.status,
                       CASE WHEN c.valid_from = c.created_at OR c.valid_from = l.valid_from
                            THEN l.event_time ELSE c.valid_from END,
                       l.depth + 1
                FROM cells c
                JOIN lineage l ON c.parent_id = l.id
                WHERE l.depth < 100
            )
            SELECT id, status, valid_from, event_time
            FROM lineage
            WHERE valid_until IS NULL
              AND event_time <> valid_from
            ORDER BY id
            """;

    private final DSLContext dsl;

    public EventTimeRepairRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** All candidates, ordered by cell id. */
    public List<EventTimeCandidate> findCandidates() {
        return dsl.fetch(CANDIDATE_SQL).map(EventTimeRepairRepository::toCandidate);
    }

    /**
     * Sets valid_from on the current row only while it still holds {@code expectedOld}.
     *
     * @return 1 if the row changed, 0 if it was revised or re-dated in the meantime
     */
    public int compareAndSetValidFrom(UUID cellId, OffsetDateTime expectedOld, OffsetDateTime newValue) {
        return dsl.execute(
                "UPDATE cells SET valid_from = ?::timestamptz WHERE id = ? AND valid_until IS NULL "
                        + "AND valid_from = ?::timestamptz",
                newValue, cellId, expectedOld);
    }

    /** Runs {@code work} in one database transaction (same mechanism as the write path). */
    public <T> T inTransaction(Supplier<T> work) {
        return dsl.transactionResult(configuration -> work.get());
    }

    private static EventTimeCandidate toCandidate(Record r) {
        return new EventTimeCandidate(
                r.get("id", UUID.class),
                r.get("status", String.class),
                r.get("valid_from", OffsetDateTime.class),
                r.get("event_time", OffsetDateTime.class));
    }
}
