package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hivemem.attachment.AttachmentRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDedupBackfillIT extends ConsumptionITSupport {

    /**
     * dedupBackfill() uses DocumentDedupRepository.findSimilarOlderCandidates, which casts
     * embedding to vector(dim) with dim derived dynamically per-call from the target cell's own
     * embedding — any fixed-size vector works; 384 here just exercises a realistic dimension.
     */
    private static final String VEC_A = unitVector(0);

    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }

    private UUID seed(String content, String embedding, String source, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, 'committed', ?::timestamptz, now())",
                id, content, embedding, source, createdAt);
        return id;
    }

    @Test
    void backfillDiscardsNewerDuplicateAndKeepsOldest() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DedupProperties props = new DedupProperties();
        DocumentDedupService service = new DocumentDedupService(
                repo, new AttachmentRepository(dsl), seaweed, props);

        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-10T10:00:00Z");
        UUID original = seed("Rechnung 4711 Betrag 199", VEC_A, "consumption:a", t0);
        UUID dup = seed("Rechnung 4711 Betrag 199", VEC_A, "consumption:b", t0.plusMinutes(5));

        DocumentDedupService.BackfillReport report = service.dedupBackfill();

        assertEquals(2, report.checked());
        assertEquals(1, report.discarded());
        assertTrue(repo.findTarget(original).isPresent(), "oldest original is kept");
        assertFalse(repo.findTarget(dup).isPresent(), "newer duplicate is discarded");
    }
}
