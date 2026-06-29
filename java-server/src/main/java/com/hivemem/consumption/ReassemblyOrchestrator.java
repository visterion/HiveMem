package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.BlankPageDetector;
import com.hivemem.ocr.PageOsd;
import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orchestrates content-based page reassembly for one staged multi-page batch: rasterize → walk in
 *  blocks calling the vision model with carry-over state → reorder into documents → split → ingest.
 *  Degrade-safe: NEVER throws to the caller. On any error the whole batch becomes one pending document
 *  (nothing is lost). */
public class ReassemblyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReassemblyOrchestrator.class);

    private final ConsumptionProperties props;
    private final PdfPageRasterizer rasterizer;
    private final PageGrouper grouper;
    private final PageReassembler reassembler;
    private final BatchSplitter splitter;
    private final AttachmentService attachments;
    private final ConsumptionFileMover mover;
    private final PageOsd osd;

    public ReassemblyOrchestrator(ConsumptionProperties props, PdfPageRasterizer rasterizer,
                                  PageGrouper grouper, PageReassembler reassembler,
                                  BatchSplitter splitter, AttachmentService attachments,
                                  ConsumptionFileMover mover, PageOsd osd) {
        this.props = props;
        this.rasterizer = rasterizer;
        this.grouper = grouper;
        this.reassembler = reassembler;
        this.splitter = splitter;
        this.attachments = attachments;
        this.mover = mover;
        this.osd = osd;
    }

    /** Reassemble one staged batch. Never throws: on any failure it degrades to one pending document. */
    public void reassemble(Path staged, byte[] pdfBytes, int pageCount) {
        String originalName = staged.getFileName().toString();
        // maxPages guard (mirrors separateStaged): the rasterizer caps at maxPages, which would silently
        // drop trailing pages. Reject explicitly so operators re-scan smaller or raise the cap.
        if (pageCount > props.getMaxPages()) {
            log.warn("Batch {} has {} pages > max-pages {}; routing to failed/ (re-scan in smaller batches "
                            + "or raise hivemem.consumption.max-pages)",
                    originalName, pageCount, props.getMaxPages());
            try { mover.moveToFailed(staged); }
            catch (Exception e) { log.error("Could not move {} to failed/: {}", originalName, e.toString()); }
            return;
        }
        try {
            List<byte[]> pages = rasterizer.rasterize(pdfBytes, props.getReassemblyRenderDpi(), props.getMaxPages());

            List<PageGrouper.BlockImage> all = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                String b64 = Base64.getEncoder().encodeToString(pages.get(i));
                all.add(new PageGrouper.BlockImage(i + 1, new VisionMultiClient.Image("image/png", b64)));
            }

            int blockSize = Math.max(1, props.getBlockSize());
            List<DocGroup> groups = new ArrayList<>();
            for (int start = 0; start < all.size(); start += blockSize) {
                List<PageGrouper.BlockImage> block = all.subList(start, Math.min(start + blockSize, all.size()));
                grouper.groupBlock(props.getRealm(), groups, block);
            }

            List<PageReassembler.ResultDoc> docs = reassembler.toDocuments(groups, pages.size());

            // Blank-page filtering (image signal): drop near-white pages from every document; drop
            // documents that become entirely blank so they never become a cell.
            Set<Integer> blank = new HashSet<>();
            if (props.isBlankFilterEnabled()) {
                for (int i = 0; i < pages.size(); i++) {
                    if (BlankPageDetector.isNearWhite(pages.get(i), props.getBlankWhiteFraction())) {
                        blank.add(i + 1); // 1-based page number
                    }
                }
            }

            // Orientation (OSD): detect rotation for the pages we will keep.
            Map<Integer, Integer> rotations = new HashMap<>();
            if (props.isOrientationCorrectionEnabled()) {
                for (int i = 0; i < pages.size(); i++) {
                    int pageNo = i + 1;
                    if (blank.contains(pageNo)) continue;
                    int rot = osd.detectRotation(pages.get(i), props.getOsdTimeoutSeconds());
                    if (rot != 0) rotations.put(pageNo, rot);
                }
            }

            List<PageReassembler.ResultDoc> keptDocs = new ArrayList<>();
            List<List<Integer>> keptGroups = new ArrayList<>();
            for (PageReassembler.ResultDoc d : docs) {
                List<Integer> kept = new ArrayList<>();
                for (Integer p : d.pages()) if (!blank.contains(p)) kept.add(p);
                if (kept.isEmpty()) continue; // entirely-blank document → no cell
                keptDocs.add(d);
                keptGroups.add(kept);
            }
            if (!blank.isEmpty()) {
                log.info("Blank-page filter: dropped {} blank page(s) from {}", blank.size(), originalName);
            }
            if (keptDocs.isEmpty()) {
                log.info("All {} page(s) of {} were blank — no documents ingested", pages.size(), originalName);
            }
            List<byte[]> parts = splitter.assemble(pdfBytes, keptGroups, rotations);
            // keptDocs and parts are index-aligned: assemble() skips empty groups, and we already
            // dropped entirely-blank documents above.

            boolean ingestFailed = false;
            for (int k = 0; k < parts.size(); k++) {
                String partName = stripPdf(originalName) + "-" + (k + 1) + ".pdf";
                try (var in = new ByteArrayInputStream(parts.get(k))) {
                    attachments.ingest(in, partName, "application/pdf", props.getRealm(),
                            null, null, null, "consumption", keptDocs.get(k).status(), "consumption:");
                } catch (Exception ingestErr) {
                    ingestFailed = true;
                    log.warn("Reassembly sub-document {} of {} failed to ingest: {}", k + 1, originalName, ingestErr.toString());
                    break;
                }
            }
            if (ingestFailed) {
                try { mover.moveToFailed(staged); }
                catch (Exception me) { log.error("Could not move {} to failed/: {}", originalName, me.toString()); }
                return;
            }
            mover.moveToProcessed(staged);
            log.info("Reassembled {} into {} documents", originalName, parts.size());
        } catch (Exception e) {
            log.warn("Reassembly failed for {}: {} - degrading to one pending document",
                    originalName, e.toString());
            degradeToPending(staged, pdfBytes, originalName);
        }
    }

    private void degradeToPending(Path staged, byte[] pdfBytes, String originalName) {
        try (var in = new ByteArrayInputStream(pdfBytes)) {
            attachments.ingest(in, originalName, "application/pdf", props.getRealm(),
                    null, null, null, "consumption", "pending", "consumption:");
        } catch (Exception e) {
            log.error("Degrade ingest failed for {}: {}", originalName, e.toString());
        }
        try { mover.moveToProcessed(staged); }
        catch (Exception e) { log.warn("Could not move {} to processed/: {}", originalName, e.toString()); }
    }

    private static String stripPdf(String name) {
        return name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }
}
