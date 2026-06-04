package com.hivemem.ocr;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.attachment.VisionBudgetTracker;
import com.hivemem.attachment.VisionClient;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
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

    @Autowired
    public OcrService(OcrProperties props,
                      OcrRepository repo,
                      SeaweedFsClient seaweed,
                      WriteToolService writeService,
                      VisionClient visionClient,
                      AttachmentProperties attachmentProps,
                      DSLContext dsl) {
        this(props, repo, seaweed, writeService, visionClient,
                (dsl != null && attachmentProps != null)
                        ? new VisionBudgetTracker(dsl, attachmentProps.getVisionDailyBudgetUsd())
                        : null,
                new TesseractRunner(props.getTesseractPath()),
                new PdfPageRasterizer());
    }

    OcrService(OcrProperties props,
               OcrRepository repo,
               SeaweedFsClient seaweed,
               WriteToolService writeService,
               VisionClient visionClient,
               VisionBudgetTracker visionBudget,
               TesseractRunner tesseract,
               PdfPageRasterizer rasterizer) {
        this.props = props;
        this.repo = repo;
        this.seaweed = seaweed;
        this.tesseract = tesseract;
        this.rasterizer = rasterizer;
        this.writeService = writeService;
        this.visionClient = visionClient;
        this.visionBudget = visionBudget;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOcrRequested(OcrRequestedEvent event) {
        processOne(event.cellId(), event.s3Key());
    }

    @Scheduled(fixedRateString = "${hivemem.ocr.backfill-interval-ms:3600000}")
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
        try {
            byte[] pdfBytes;
            try (InputStream in = seaweed.download(s3Key)) {
                pdfBytes = in.readAllBytes();
            }
            List<byte[]> pages = rasterizer.rasterize(pdfBytes, props.getRenderDpi(), props.getMaxPages());

            boolean visionEnabled = props.isVisionFallbackEnabled()
                    && visionClient != null
                    && visionClient.isEnabled()
                    && visionBudget != null;
            int visionPagesUsed = 0;

            List<String> pageTexts = new ArrayList<>(pages.size());
            for (int i = 0; i < pages.size(); i++) {
                String text;
                try {
                    text = tesseract.ocr(pages.get(i), props.getLanguages(), props.getCallTimeoutSeconds());
                } catch (Exception e) {
                    log.warn("OCR page {} of cell {} failed: {}", i + 1, cellId, e.getMessage());
                    text = "";
                }

                if (visionEnabled
                        && text.length() < props.getVisionFallbackMinCharsPerPage()
                        && visionPagesUsed < props.getVisionFallbackMaxPagesPerDoc()
                        && visionBudget.canSpend()) {
                    String visionText = transcribeWithVision(pages.get(i), cellId, i + 1);
                    if (visionText != null) {
                        text = visionText;
                        visionPagesUsed++;
                    }
                }

                if (text.isEmpty()) {
                    text = "[page=" + (i + 1) + ": OCR failed]";
                }
                pageTexts.add(text);
            }

            if (visionPagesUsed > 0) {
                log.info("Vision-OCR fallback used on {} of {} pages for cell {}",
                        visionPagesUsed, pages.size(), cellId);
            }

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pageTexts.size(); i++) {
                out.append("[page=").append(i + 1).append("]\n").append(pageTexts.get(i)).append("\n\n");
            }

            // Push the OCR'd text into the cell. reviseCell will recompute the embedding
            // (encodeForCell), and since the new content is long with no summary, the
            // SummarizerService picks it up automatically via needs_summary.
            var reviseResult = writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, out.toString().trim(), null);
            // Remove tag from the original cell AND from the new revision (which inherits tags).
            repo.removeOcrPendingTag(cellId);
            Object newIdObj = reviseResult.get("new_id");
            if (newIdObj != null) {
                repo.removeOcrPendingTag(UUID.fromString(newIdObj.toString()));
            }
        } catch (Exception e) {
            log.error("OCR failed for cell {}: {}", cellId, e.getMessage(), e);
            repo.tagFailed(cellId);
        }
    }

    private String transcribeWithVision(byte[] pngBytes, UUID cellId, int pageNum) {
        try {
            VisionClient.VisionResult vr = visionClient.transcribe(pngBytes, "image/png");
            visionBudget.recordCall(vr.inputTokens(), vr.outputTokens());
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
