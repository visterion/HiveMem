package com.hivemem.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// Gated on the consumption flag (already true in prod) because embedding_pending cells only arise
// from the consumption ingest path. This also keeps the @Scheduled bean out of the DB-less
// HiveMemApplicationTest context (which excludes DataSource/Flyway/jOOQ), mirroring the sibling
// sweeps (SummarizerService/OcrService/ConsumptionRecoverySweep) that are all @ConditionalOnProperty.
@Service
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class EmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);

    private final EmbeddingBackfillRepository repo;
    private final EmbeddingClient client;
    private final int batchSize;

    public EmbeddingBackfillService(EmbeddingBackfillRepository repo, EmbeddingClient client,
            @Value("${hivemem.embedding.backfill-batch-size:50}") int batchSize) {
        this.repo = repo;
        this.client = client;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedRateString = "${hivemem.embedding.backfill-interval-ms:300000}")
    public void backfill() {
        List<UUID> ids = repo.findCellsMissingEmbedding(batchSize);
        for (UUID id : ids) {
            try {
                var snap = repo.findSnapshot(id).orElse(null);
                if (snap == null || snap.content() == null || snap.content().isBlank()) continue;
                List<Float> vec = client.encodeForCell(snap.content(), snap.summary());
                if (vec == null) continue;
                repo.setEmbedding(id, vec.toArray(Float[]::new));
            } catch (EmbeddingUnavailableException e) {
                log.warn("Embedding service still unavailable; deferring backfill (had {} pending)", ids.size());
                return;
            } catch (Exception e) {
                log.warn("Embedding backfill failed for cell {}: {}", id, e.getMessage());
            }
        }
    }
}
