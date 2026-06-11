package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One-time, idempotent backfill of EXIF metadata for image attachments uploaded before SP-D1. */
@Component
public class ImageMetaBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageMetaBackfillRunner.class);

    private final ImageMetaRepository repo;
    private final SeaweedFsClient seaweedFs;
    private final ExifExtractor exif;
    private final AttachmentRepository attRepo;
    private final ApplicationEventPublisher events;

    public ImageMetaBackfillRunner(ImageMetaRepository repo, SeaweedFsClient seaweedFs,
                                   ExifExtractor exif, AttachmentRepository attRepo,
                                   ApplicationEventPublisher events) {
        this.repo = repo;
        this.seaweedFs = seaweedFs;
        this.exif = exif;
        this.attRepo = attRepo;
        this.events = events;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        backfill();
    }

    /** Process every image attachment lacking a metadata row. Safe to re-run (no-op once filled). */
    public void backfill() {
        List<UUID> missing = repo.findImageAttachmentsWithoutMeta();
        if (missing.isEmpty()) return;
        int processed = 0;
        for (UUID attId : missing) {
            try {
                Optional<Map<String, Object>> row = attRepo.findById(attId);
                if (row.isEmpty()) continue;
                String key = (String) row.get().get("s3_key_original");
                byte[] bytes = seaweedFs.downloadBytes(key);
                ExifData data = exif.extract(bytes);
                boolean hasGps = data.gpsLat() != null && data.gpsLon() != null;
                repo.upsert(attId, data, hasGps ? "pending" : "none");
                if (hasGps) {
                    events.publishEvent(new GeocodeRequestedEvent(attId, data.gpsLat(), data.gpsLon()));
                }
                processed++;
            } catch (Exception e) {
                log.warn("Image EXIF backfill failed for {}: {}", attId, e.getMessage());
            }
        }
        log.info("Image EXIF backfill complete: processed={} of {} candidates", processed, missing.size());
    }
}
