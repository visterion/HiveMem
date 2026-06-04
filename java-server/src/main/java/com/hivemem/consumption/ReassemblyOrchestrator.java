package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    public ReassemblyOrchestrator(ConsumptionProperties props, PdfPageRasterizer rasterizer,
                                  PageGrouper grouper, PageReassembler reassembler,
                                  BatchSplitter splitter, AttachmentService attachments,
                                  ConsumptionFileMover mover) {
        this.props = props;
        this.rasterizer = rasterizer;
        this.grouper = grouper;
        this.reassembler = reassembler;
        this.splitter = splitter;
        this.attachments = attachments;
        this.mover = mover;
    }

    /** Reassemble one staged batch. Never throws: on any failure it degrades to one pending document. */
    public void reassemble(Path staged, byte[] pdfBytes, int pageCount) {
        String originalName = staged.getFileName().toString();
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
            List<byte[]> parts = splitter.assemble(pdfBytes, docs.stream().map(PageReassembler.ResultDoc::pages).toList());
            // docs and parts are index-aligned: assemble() skips empty groups, but reassembler never emits one.

            for (int k = 0; k < parts.size(); k++) {
                String partName = stripPdf(originalName) + "-" + (k + 1) + ".pdf";
                try (var in = new ByteArrayInputStream(parts.get(k))) {
                    attachments.ingest(in, partName, "application/pdf", props.getRealm(),
                            null, null, null, "consumption", docs.get(k).status(), "consumption:");
                }
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
