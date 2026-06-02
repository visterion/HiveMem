package com.hivemem.consumption;

import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class SeparationJobRepository {

    private final DSLContext dsl;

    public SeparationJobRepository(DSLContext dsl) { this.dsl = dsl; }

    public void create(UUID correlationId, String s3Key, String originalName,
                       String sourcePath, int pageCount, String realm) {
        dsl.execute("""
                INSERT INTO consumption_jobs
                  (correlation_id, s3_key, original_name, source_path, page_count, realm)
                VALUES (?, ?, ?, ?, ?, ?)
                """, correlationId, s3Key, originalName, sourcePath, pageCount, realm);
    }

    public Optional<Job> findAwaiting(UUID correlationId) {
        Record r = dsl.fetchOne("""
                SELECT id, correlation_id, s3_key, original_name, source_path, page_count, realm,
                       status, dispatch_count
                FROM consumption_jobs WHERE correlation_id = ? AND status = 'awaiting'
                """, correlationId);
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public List<Job> findStale(int olderThanSeconds, int limit) {
        var rows = dsl.fetch("""
                SELECT id, correlation_id, s3_key, original_name, source_path, page_count, realm,
                       status, dispatch_count
                FROM consumption_jobs
                WHERE status = 'awaiting' AND updated_at < now() - (? * interval '1 second')
                ORDER BY updated_at LIMIT ?
                """, olderThanSeconds, limit);
        List<Job> out = new ArrayList<>();
        for (Record r : rows) out.add(map(r));
        return out;
    }

    public void markDone(UUID correlationId) {
        dsl.execute("UPDATE consumption_jobs SET status='done', updated_at=now() WHERE correlation_id=?",
                correlationId);
    }

    public void markFailed(UUID correlationId) {
        dsl.execute("UPDATE consumption_jobs SET status='failed', updated_at=now() WHERE correlation_id=?",
                correlationId);
    }

    public void bumpDispatch(UUID correlationId) {
        dsl.execute("UPDATE consumption_jobs SET dispatch_count = dispatch_count + 1, updated_at=now() "
                + "WHERE correlation_id=?", correlationId);
    }

    private static Job map(Record r) {
        return new Job(
                r.get("id", UUID.class),
                r.get("correlation_id", UUID.class),
                r.get("s3_key", String.class),
                r.get("original_name", String.class),
                r.get("source_path", String.class),
                r.get("page_count", Integer.class),
                r.get("realm", String.class),
                r.get("status", String.class),
                r.get("dispatch_count", Integer.class));
    }

    public record Job(UUID id, UUID correlationId, String s3Key, String originalName,
                      String sourcePath, int pageCount, String realm, String status, int dispatchCount) {}
}
