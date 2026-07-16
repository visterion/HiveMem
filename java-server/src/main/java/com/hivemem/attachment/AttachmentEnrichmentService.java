package com.hivemem.attachment;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.queen.ArchivistTrigger;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.attachment.enabled", havingValue = "true")
public class AttachmentEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentEnrichmentService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-enrichment", AuthRole.ADMIN);

    private final AttachmentProperties props;
    private final KrokiClient krokiClient;
    private final VisionClient visionClient;
    private final SeaweedFsClient seaweedFs;
    private final AttachmentRepository attachmentRepo;
    private final WriteToolService writeService;
    private final DSLContext dsl;
    private final ExtractionProfileRegistry profileRegistry;
    private final VisionBudgetTracker visionBudget;
    private final ArchivistTrigger archivistTrigger;

    public AttachmentEnrichmentService(AttachmentProperties props,
                                       KrokiClient krokiClient,
                                       VisionClient visionClient,
                                       SeaweedFsClient seaweedFs,
                                       AttachmentRepository attachmentRepo,
                                       WriteToolService writeService,
                                       DSLContext dsl,
                                       ExtractionProfileRegistry profileRegistry,
                                       VisionBudgetTracker visionBudget,
                                       ArchivistTrigger archivistTrigger) {
        this.props = props;
        this.krokiClient = krokiClient;
        this.visionClient = visionClient;
        this.seaweedFs = seaweedFs;
        this.attachmentRepo = attachmentRepo;
        this.writeService = writeService;
        this.dsl = dsl;
        this.profileRegistry = profileRegistry;
        this.visionBudget = visionBudget;
        this.archivistTrigger = archivistTrigger;
    }

    // ── Event listeners (after-commit) ─────────────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onThumbnailRequested(ThumbnailRequestedEvent ev) {
        if (!krokiClient.isEnabled()) return;
        renderAndStore(ev.attachmentId(), ev.cellId(), ev.fileHash(), ev.mimeType(), ev.diagramSource());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisionRequested(VisionDescriptionRequestedEvent ev) {
        if (!visionClient.isEnabled()) return;
        if (!visionBudget.canSpend()) {
            log.info("Vision budget exhausted; deferring cell {}", ev.cellId());
            return;
        }
        describeAndRevise(ev.attachmentId(), ev.cellId(), ev.s3KeyOriginal(), ev.mimeType());
    }

    // ── Backfill (scheduled hourly) ────────────────────────────────────────

    @Scheduled(fixedRateString = "${hivemem.attachment.kroki-backfill-interval:PT1H}")
    public void backfillThumbnails() {
        if (!krokiClient.isEnabled()) return;
        List<AttachmentRepository.DiagramRow> rows =
                attachmentRepo.findDiagramsWithoutThumbnail(KrokiClient.MIME_TO_FORMAT.keySet(), 50);
        if (rows.isEmpty()) return;
        log.info("Kroki backfill: {} diagrams pending", rows.size());
        for (var r : rows) {
            renderAndStore(r.attachmentId(), r.cellId(), r.fileHash(), r.mimeType(), r.diagramSource());
        }
    }

    @Scheduled(fixedRateString = "${hivemem.attachment.vision-backfill-interval:PT1H}")
    public void backfillVisionDescriptions() {
        if (!visionClient.isEnabled()) return;
        if (!visionBudget.canSpend()) return;
        List<UUID> cellIds = attachmentRepo.findCellsWithVisionPending(20);
        if (cellIds.isEmpty()) return;
        log.info("Vision backfill: {} cells pending", cellIds.size());
        for (UUID cellId : cellIds) {
            if (!visionBudget.canSpend()) break;
            attachmentRepo.findAttachmentForCell(cellId).ifPresent(att ->
                    describeAndRevise(att.attachmentId(), cellId, att.s3KeyOriginal(), att.mimeType()));
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    void renderAndStore(UUID attId, UUID cellId, String fileHash, String mimeType, String source) {
        try {
            krokiClient.render(mimeType, source).ifPresentOrElse(png -> {
                try {
                    String key = fileHash + "-thumb.png";
                    seaweedFs.uploadBytes(key, png, "image/png");
                    attachmentRepo.updateThumbnailKey(attId, key);
                    removeTag(cellId, "kroki_pending");
                    archivistTrigger.maybeTrigger(cellId);
                    log.debug("Kroki thumbnail stored for attachment {}", attId);
                } catch (Exception e) {
                    log.warn("Failed to store Kroki thumbnail for {}: {}", attId, e.getMessage());
                }
            }, () -> {
                // Render returned empty (4xx / unsupported / blank): mark failed, stop retrying.
                tagFailed(cellId, "kroki_failed");
                removeTag(cellId, "kroki_pending");
                archivistTrigger.maybeTrigger(cellId);
            });
        } catch (Exception e) {
            log.warn("Kroki render error for attachment {}: {}", attId, e.getMessage());
        }
    }

    void describeAndRevise(UUID attId, UUID cellId, String s3KeyOriginal, String mimeType) {
        // Atomic claim: the AFTER_COMMIT event worker (onVisionRequested) and the scheduled
        // hourly backfill can race on the same cell — without the claim both pay a Vision LLM
        // call and produce competing revisions. Mirrors SummarizerService.summarizeOne /
        // OcrService.processOne.
        if (!attachmentRepo.tryClaim(cellId)) {
            log.debug("Vision: cell {} already claimed by another worker, skipping", cellId);
            return;
        }
        try {
            describeAndReviseClaimed(attId, cellId, s3KeyOriginal, mimeType);
        } finally {
            attachmentRepo.clearClaim(cellId);
        }
    }

    private void describeAndReviseClaimed(UUID attId, UUID cellId, String s3KeyOriginal, String mimeType) {
        byte[] imageBytes;
        try (InputStream s = seaweedFs.download(s3KeyOriginal)) {
            imageBytes = s.readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to download image {} for vision: {}", s3KeyOriginal, e.getMessage());
            return;
        }
        try {
            VisionClient.ImageDescriptionResult r;
            visionBudget.beginCall();
            try {
                r = visionClient.describeImage(imageBytes, mimeType);
                visionBudget.recordCall(r.inputTokens(), r.outputTokens());
            } finally {
                visionBudget.endCall();
            }

            if (r.content() == null || r.content().isBlank()) {
                log.info("Vision returned empty content for cell {} — tagging vision_failed", cellId);
                tagFailed(cellId, "vision_failed");
                removeTag(cellId, "vision_pending");
                archivistTrigger.maybeTrigger(cellId);
                return;
            }

            // reviseCell closes the old cell and creates a NEW revision (which inherits tags).
            // All tag work must target the new id, or the tags land on a dead cell and the
            // live revision keeps vision_pending forever (re-describe loop). Mirrors
            // OcrService.processOne.
            var reviseResult = writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, r.content(), null);
            UUID targetId = cellId;
            Object newIdObj = reviseResult.get("new_id");
            if (newIdObj != null) {
                targetId = UUID.fromString(newIdObj.toString());
            }

            com.hivemem.extraction.ExtractionProfile profile =
                    profileRegistry.resolveImageSubType(r.subType());
            cleanOldSubtypeTags(targetId);
            applyTag(targetId, "subtype_" + r.subType());
            for (String t : profile.tagsToApply()) {
                applyTag(targetId, t);
            }
            // Remove the pending tag from the superseded cell AND the new revision.
            removeTag(cellId, "vision_pending");
            if (!targetId.equals(cellId)) {
                removeTag(targetId, "vision_pending");
            }
            archivistTrigger.maybeTrigger(targetId);
            log.debug("Vision sub-type {} stored for cell {}", r.subType(), targetId);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Vision 429 for cell {} — will retry on backfill", cellId);
        } catch (VisionClient.OversizeImageException e) {
            tagFailed(cellId, "vision_failed");
            removeTag(cellId, "vision_pending");
            archivistTrigger.maybeTrigger(cellId);
            log.info("Vision skipped (oversize) for cell {}", cellId);
        } catch (IllegalArgumentException e) {
            tagFailed(cellId, "vision_failed");
            removeTag(cellId, "vision_pending");
            archivistTrigger.maybeTrigger(cellId);
            log.info("Vision skipped (unsupported mime) for cell {}: {}", cellId, e.getMessage());
        } catch (Exception e) {
            log.warn("Vision describe failed for cell {}: {}", cellId, e.getMessage());
        }
    }

    private void applyTag(UUID cellId, String tag) {
        dsl.execute(
                "UPDATE cells SET tags = "
                        + "CASE WHEN ? = ANY(tags) THEN tags ELSE array_append(tags, ?) END "
                        + "WHERE id = ?", tag, tag, cellId);
    }

    private void cleanOldSubtypeTags(UUID cellId) {
        dsl.execute(
                "UPDATE cells SET tags = array_remove(array_remove(array_remove(tags, ?), ?), ?) "
                        + "WHERE id = ?",
                "subtype_whiteboard_photo", "subtype_document_scan",
                "subtype_photo_general", cellId);
    }

    private void removeTag(UUID cellId, String tag) {
        dsl.execute("UPDATE cells SET tags = array_remove(tags, ?) WHERE id = ?", tag, cellId);
    }

    private void tagFailed(UUID cellId, String tag) {
        applyTag(cellId, tag);
    }
}
