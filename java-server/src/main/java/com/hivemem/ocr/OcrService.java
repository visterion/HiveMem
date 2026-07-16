package com.hivemem.ocr;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.attachment.VisionBudgetTracker;
import com.hivemem.attachment.VisionClient;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.queen.ArchivistTrigger;
import com.hivemem.summarize.NeedsSummaryDecider;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.ocr.enabled", havingValue = "true")
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-ocr", AuthRole.ADMIN);

    private final OcrProperties props;
    private final OcrRepository repo;
    private final SeaweedFsClient seaweed;
    private final TesseractRunner tesseract;
    private final PdfPageRasterizer rasterizer;
    private final WriteToolService writeService;
    private final VisionClient visionClient;
    private final VisionBudgetTracker visionBudget;
    private final DocumentDedupService dedup; // may be null (tests / dedup unavailable)
    private ArchivistTrigger archivistTrigger; // set via the @Autowired public constructor only

    @Autowired
    public OcrService(OcrProperties props,
                      OcrRepository repo,
                      SeaweedFsClient seaweed,
                      WriteToolService writeService,
                      VisionClient visionClient,
                      AttachmentProperties attachmentProps,
                      DSLContext dsl,
                      DocumentDedupService dedup,
                      ArchivistTrigger archivistTrigger) {
        this(props, repo, seaweed, writeService, visionClient,
                (dsl != null && attachmentProps != null)
                        ? new VisionBudgetTracker(dsl, attachmentProps.getVisionDailyBudgetUsd())
                        : null,
                new TesseractRunner(props.getTesseractPath()),
                new PdfPageRasterizer(),
                dedup);
        this.archivistTrigger = archivistTrigger;
    }

    OcrService(OcrProperties props,
               OcrRepository repo,
               SeaweedFsClient seaweed,
               WriteToolService writeService,
               VisionClient visionClient,
               VisionBudgetTracker visionBudget,
               TesseractRunner tesseract,
               PdfPageRasterizer rasterizer,
               DocumentDedupService dedup) {
        this.props = props;
        this.repo = repo;
        this.seaweed = seaweed;
        this.tesseract = tesseract;
        this.rasterizer = rasterizer;
        this.writeService = writeService;
        this.visionClient = visionClient;
        this.visionBudget = visionBudget;
        this.dedup = dedup;
    }

    private void notifyArchivist(UUID cellId) {
        if (archivistTrigger != null) archivistTrigger.maybeTrigger(cellId);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOcrRequested(OcrRequestedEvent event) {
        processOne(event.cellId(), event.s3Key());
    }

    @Scheduled(fixedRateString = "${hivemem.ocr.backfill-interval:PT1H}")
    public void backfill() {
        List<UUID> ids = repo.findCellsPendingOcr(props.getBackfillBatchSize());
        for (UUID id : ids) {
            var info = repo.findAttachmentForCell(id).orElse(null);
            if (info == null) {
                log.warn("OCR backfill: cell {} has no attachment, skipping", id);
                continue;
            }
            processOne(id, info.s3Key());
        }
    }

    void processOne(UUID cellId, String s3Key) {
        // Atomic claim: the AFTER_COMMIT event worker (onOcrRequested) and the scheduled hourly
        // backfill can race on the same cell — without the claim both run OCR (and possibly
        // Vision fallback) and produce competing revisions. Mirrors SummarizerService.summarizeOne.
        if (!repo.tryClaim(cellId)) {
            log.debug("OCR: cell {} already claimed by another worker, skipping", cellId);
            return;
        }
        try {
            processClaimed(cellId, s3Key);
        } finally {
            repo.clearClaim(cellId);
        }
    }

    private void processClaimed(UUID cellId, String s3Key) {
        try {
            byte[] pdfBytes;
            try (InputStream in = seaweed.download(s3Key)) {
                pdfBytes = in.readAllBytes();
            }

            boolean visionEnabled = props.isVisionFallbackEnabled()
                    && visionClient != null
                    && visionClient.isEnabled()
                    && visionBudget != null;

            // Mutable counters/accumulator captured by the per-page callback below. Rendering
            // is now page-by-page (PdfPageRasterizer no longer materializes every page's PNG
            // bytes up front — up to 50 pages @300 DPI held in memory for the whole OCR run
            // was a real heap risk on large documents), so this is where "N of M pages" and
            // the kept-text accumulation used to live around a simple indexed for-loop.
            java.util.concurrent.atomic.AtomicInteger visionPagesUsed = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger blankCount = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger totalPages = new java.util.concurrent.atomic.AtomicInteger();
            List<String> keptTexts = new ArrayList<>();

            rasterizer.rasterize(pdfBytes, props.getRenderDpi(), props.getMaxPages(), (pageIndex, pngBytes) -> {
                totalPages.incrementAndGet();
                String text;
                try {
                    text = tesseract.ocr(pngBytes, props.getLanguages(), props.getCallTimeoutSeconds());
                } catch (Exception e) {
                    log.warn("OCR page {} of cell {} failed: {}", pageIndex + 1, cellId, e.getMessage());
                    text = "";
                }

                if (visionEnabled
                        && text.length() < props.getVisionFallbackMinCharsPerPage()
                        && visionPagesUsed.get() < props.getVisionFallbackMaxPagesPerDoc()
                        && visionBudget.canSpend()) {
                    String visionText = transcribeWithVision(pngBytes, cellId, pageIndex + 1);
                    if (visionText != null) {
                        text = visionText;
                        visionPagesUsed.incrementAndGet();
                    }
                }

                // Combo blank check: drop a page ONLY when it is BOTH near-white AND produced no text,
                // so an OCR failure on a page that actually has ink is never silently dropped.
                boolean blank = props.isDropBlankPages()
                        && text.isBlank()
                        && BlankPageDetector.isNearWhite(pngBytes, props.getBlankWhiteFraction());
                if (blank) {
                    blankCount.incrementAndGet();
                    return;
                }
                String kept = text.isBlank()
                        ? "[page: OCR produced no text]" // non-white page kept with a marker (not dropped)
                        : text;
                keptTexts.add(kept);
            });

            if (visionPagesUsed.get() > 0) {
                log.info("Vision-OCR fallback used on {} of {} pages for cell {}",
                        visionPagesUsed.get(), totalPages.get(), cellId);
            }

            if (keptTexts.isEmpty()) {
                log.info("OCR: cell {} is entirely blank ({} pages) — soft-deleting", cellId, blankCount.get());
                repo.removeOcrPendingTag(cellId);
                repo.softDeleteBlankCell(cellId);
                return;
            }

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < keptTexts.size(); i++) {
                out.append("[page=").append(i + 1).append("]\n").append(keptTexts.get(i)).append("\n\n");
            }

            // Push the OCR'd text into the cell. reviseCell will recompute the embedding
            // (encodeForCell), and since the new content is long with no summary, the
            // SummarizerService picks it up automatically via needs_summary.
            String content = out.toString().trim();
            var reviseResult = writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, content, null);
            // Remove tag from the original cell AND from the new revision (which inherits tags).
            repo.removeOcrPendingTag(cellId);
            Object newIdObj = reviseResult.get("new_id");
            UUID liveId = cellId;
            if (newIdObj != null) {
                UUID newId = UUID.fromString(newIdObj.toString());
                liveId = newId;
                repo.removeOcrPendingTag(newId);
                // Content-based dedup. Long docs have no embedding yet here (it is produced later by
                // the summarizer, which runs its own dedup pass); only short docs (≤ threshold) are
                // embedded directly at revise time, so only those can be deduped now.
                if (dedup != null && !NeedsSummaryDecider.needsSummary(content, null)) {
                    dedup.findAndDiscardDuplicate(newId);
                }
            }
            notifyArchivist(liveId);
        } catch (Exception e) {
            log.error("OCR failed for cell {}: {}", cellId, e.getMessage(), e);
            repo.tagFailed(cellId);
            notifyArchivist(cellId);
        }
    }

    private String transcribeWithVision(byte[] pngBytes, UUID cellId, int pageNum) {
        try {
            VisionClient.VisionResult vr;
            visionBudget.beginCall();
            try {
                vr = visionClient.transcribe(pngBytes, "image/png");
                visionBudget.recordCall(vr.inputTokens(), vr.outputTokens());
            } finally {
                visionBudget.endCall();
            }
            return vr.description();
        } catch (VisionClient.OversizeImageException e) {
            log.info("Vision-OCR skipped (oversize) page {} of cell {}", pageNum, cellId);
            return null;
        } catch (Exception e) {
            log.warn("Vision-OCR failed page {} of cell {}: {}", pageNum, cellId, e.getMessage());
            return null;
        }
    }
}
