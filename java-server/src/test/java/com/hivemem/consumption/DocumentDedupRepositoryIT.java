package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDedupRepositoryIT extends ConsumptionITSupport {

    /**
     * findSimilarOlderCandidates casts embedding to vector(dim), where dim is derived at query
     * time from the target cell's own embedding (vector_dims) rather than hardcoded — so any
     * fixed-size vector works here. VEC_A/VEC_B are 384-dimensional unit vectors on two different
     * axes (cosine distance 1.0 apart) simply to exercise a realistic, larger dimension.
     */
    private static final String VEC_A = unitVector(0);
    private static final String VEC_B = unitVector(1);

    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }

    private UUID seedCell(String content, String embedding, String source,
                          String status, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, content, embedding, source, status, createdAt);
        return id;
    }

    private void linkAttachment(UUID cellId, UUID attachmentId) {
        dsl.execute(
                "INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source) "
                + "VALUES (?, ?, true)", cellId, attachmentId);
    }

    @Test
    void findsOlderSimilarScanCandidate() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID original = seedCell("Rechnung 4711", VEC_A, "consumption:a",
                "committed", t0);
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b",
                "committed", t0.plusMinutes(5));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(dup, 0.92, 10);

        assertEquals(1, cands.size());
        assertEquals(original, cands.get(0).id());
        assertTrue(cands.get(0).cosine() >= 0.99);
    }

    @Test
    void ignoresNonScanAndNewerAndDissimilar() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-02T10:00:00Z");
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0);
        seedCell("Rechnung 4711", VEC_A, "manual:x", "committed", t0.minusMinutes(5)); // not a scan
        seedCell("Rechnung 4711", VEC_A, "consumption:c", "committed", t0.plusMinutes(5)); // newer
        seedCell("Mietvertrag", VEC_B, "consumption:d", "committed", t0.minusMinutes(5)); // dissimilar vec

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(dup, 0.92, 10);

        assertTrue(cands.isEmpty(), "expected no candidates, got " + cands);
    }

    @Test
    void softDeleteAndReferenceCount() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-03T10:00:00Z");
        UUID att = UUID.randomUUID();
        dsl.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, "
                + "size_bytes, s3_key_original, uploaded_by) VALUES (?, ?, 'application/pdf', 'x.pdf', 1, ?, 'system')",
                att, "hash-" + att, "key-" + att);
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0);
        linkAttachment(dup, att);

        assertEquals(1, repo.countOtherLiveCellsForAttachment(att, UUID.randomUUID()));
        assertTrue(repo.softDeleteCell(dup) >= 1);
        // After soft-delete the dup is no longer "live".
        assertEquals(0, repo.countOtherLiveCellsForAttachment(att, dup));

        var keys = repo.findAttachmentKeysForCell(dup);
        assertTrue(keys.isPresent());
        assertEquals(att, keys.get().attachmentId());
        assertFalse(repo.findTarget(dup).isPresent(), "soft-deleted cell is not a valid target");
    }

    @Test
    void linkAndSoftDeleteWritesTunnelAndSoftDeletesAtomically() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-04T10:00:00Z");
        UUID original = seedCell("Rechnung 4711", VEC_A, "consumption:a", "committed", t0);
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0.plusMinutes(5));

        repo.linkAndSoftDelete(dup, original, "auto-dedup note", "system-dedup");

        assertFalse(repo.findTarget(dup).isPresent(), "duplicate must be soft-deleted");
        int tunnels = dsl.fetchOne(
                "SELECT count(*) AS n FROM tunnels "
                + "WHERE from_cell = ? AND to_cell = ? AND relation = 'duplicate_of'",
                dup, original).get("n", Long.class).intValue();
        assertEquals(1, tunnels, "exactly one duplicate_of tunnel must be written");
    }

    @Test
    void findTargetIgnoresNonCommitted() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-05T10:00:00Z");
        UUID pending = seedCell("Rechnung 4711", VEC_A, "consumption:p", "pending", t0);
        assertFalse(repo.findTarget(pending).isPresent(), "pending cell is not a valid dedup target");
    }
}
