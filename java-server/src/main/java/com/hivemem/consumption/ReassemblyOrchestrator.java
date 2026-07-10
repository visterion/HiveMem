package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.BlankPageDetector;
import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orchestrates content-based page reassembly for one staged multi-page batch: rasterize → orient
 *  (A/B) → extract per-page metadata → assemble mailings (text) → split with rotations → ingest.
 *  Degrade-safe: NEVER throws to the caller. On any error the whole batch becomes one pending document
 *  (nothing is lost). */
public class ReassemblyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReassemblyOrchestrator.class);

    private final ConsumptionProperties props;
    private final PdfPageRasterizer rasterizer;
    private final PageOrienter orienter;
    private final PageMetadataExtractor extractor;
    private final MailingAssembler assembler;
    private final PageReassembler reassembler;
    private final BatchSplitter splitter;
    private final AttachmentService attachments;
    private final ConsumptionFileMover mover;

    public ReassemblyOrchestrator(ConsumptionProperties props, PdfPageRasterizer rasterizer,
                                  PageOrienter orienter, PageMetadataExtractor extractor,
                                  MailingAssembler assembler, PageReassembler reassembler,
                                  BatchSplitter splitter, AttachmentService attachments,
                                  ConsumptionFileMover mover) {
        this.props = props;
        this.rasterizer = rasterizer;
        this.orienter = orienter;
        this.extractor = extractor;
        this.assembler = assembler;
        this.reassembler = reassembler;
        this.splitter = splitter;
        this.attachments = attachments;
        this.mover = mover;
    }

    /** Reassemble one staged batch. Never throws: on any failure it degrades to one pending document. */
    public void reassemble(Path staged, byte[] pdfBytes, int pageCount) {
        reassemble(staged, pdfBytes, pageCount, null, null);
    }

    /** Reassemble one staged batch. Never throws: on any failure it degrades to one pending document.
     *  @param hash     sha256 of pdfBytes (nullable — ledger is skipped when null)
     *  @param fileRepo ledger repository (nullable)
     */
    public void reassemble(Path staged, byte[] pdfBytes, int pageCount, String hash, ConsumptionFileRepository fileRepo) {
        String originalName = staged.getFileName().toString();
        // maxPages guard (mirrors separateStaged): the rasterizer caps at maxPages, which would silently
        // drop trailing pages. Reject explicitly so operators re-scan smaller or raise the cap.
        if (pageCount > props.getMaxPages()) {
            log.warn("Batch {} has {} pages > max-pages {}; routing to failed/ (re-scan in smaller batches "
                            + "or raise hivemem.consumption.max-pages)",
                    originalName, pageCount, props.getMaxPages());
            if (fileRepo != null) fileRepo.markFailed(hash,
                    "page count " + pageCount + " exceeds max-pages " + props.getMaxPages());
            moveToFailedTracked(staged, hash, fileRepo);
            return;
        }
        try {
            List<byte[]> pages = rasterizer.rasterize(pdfBytes, props.getReassemblyRenderDpi(), props.getMaxPages());

            // Pass 1 — per-page orientation + blankness (forced-choice A/B; validated 15/15).
            Map<Integer, Integer> rotations = new HashMap<>();
            Set<Integer> blank = new HashSet<>();
            List<byte[]> upright = new ArrayList<>(pages);
            for (int i = 0; i < pages.size(); i++) {
                int pageNo = i + 1;
                PageOrienter.PageOrientation o = orienter.orient(props.getRealm(), pageNo, pages.get(i));
                if (o.rotation() != 0) {
                    rotations.put(pageNo, o.rotation());
                    upright.set(i, PageOrienter.rotate180Png(pages.get(i)));
                }
                if (o.blank()) blank.add(pageNo);
                // Heartbeat inside the per-page loop: each orientation costs one (possibly retried)
                // LLM call, so a single pass over a large batch can outlast the recovery sweep's
                // stale threshold — bump updated_at per page so a live run can't be mistaken for a
                // crash-stranded file and re-staged mid-run (see consumption.md reassembly note).
                if (hash != null && fileRepo != null) fileRepo.touch(hash);
            }

            // Pass 2 — per-page metadata from the upright renders.
            List<PageMetadataExtractor.PageMetadata> meta = new ArrayList<>();
            for (int i = 0; i < upright.size(); i++) {
                PageMetadataExtractor.PageMetadata m =
                        extractor.extract(props.getRealm(), i + 1, upright.get(i));
                if (m.blank()) blank.add(m.page());
                meta.add(m);
                if (hash != null && fileRepo != null) fileRepo.touch(hash); // heartbeat (see pass 1)
            }

            // Pass 3 — text-only mailing assembly (throws on garbage → degrade path).
            List<DocGroup> groups = assembler.assemble(props.getRealm(), meta);

            List<PageReassembler.ResultDoc> docs = reassembler.toDocuments(groups, pages.size());

            // Blank-page filtering: union of the LLM signals (passes 1+2, already in `blank`)
            // and the pixel signal; drop entirely-blank documents so they never become a cell.
            if (props.isBlankFilterEnabled()) {
                for (int i = 0; i < pages.size(); i++) {
                    if (BlankPageDetector.isNearWhite(pages.get(i), props.getBlankWhiteFraction())) {
                        blank.add(i + 1);
                    }
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
                if (fileRepo != null) fileRepo.markFailed(hash, "reassembly sub-doc ingest failed");
                moveToFailedTracked(staged, hash, fileRepo);
                return;
            }
            mover.moveToProcessed(staged);
            if (fileRepo != null) fileRepo.markDone(hash);
            log.info("Reassembled {} into {} documents", originalName, parts.size());
        } catch (Exception e) {
            log.warn("Reassembly failed for {}: {} - degrading to one pending document",
                    originalName, e.toString());
            degradeToPending(staged, pdfBytes, originalName, hash, fileRepo);
        }
    }


    private void degradeToPending(Path staged, byte[] pdfBytes, String originalName) {
        degradeToPending(staged, pdfBytes, originalName, null, null);
    }

    private void degradeToPending(Path staged, byte[] pdfBytes, String originalName,
                                  String hash, ConsumptionFileRepository fileRepo) {
        boolean ingested = false;
        try (var in = new ByteArrayInputStream(pdfBytes)) {
            attachments.ingest(in, originalName, "application/pdf", props.getRealm(),
                    null, null, null, "consumption", "pending", "consumption:");
            ingested = true;
        } catch (Exception e) {
            log.error("Degrade ingest failed for {}: {}", originalName, e.toString());
        }
        if (ingested) {
            try { mover.moveToProcessed(staged); }
            catch (Exception e) { log.warn("Could not move {} to processed/: {}", originalName, e.toString()); }
            // Batch was salvaged as one pending doc — terminal, not stranded
            if (fileRepo != null) fileRepo.markDone(hash);
        } else {
            if (fileRepo != null) fileRepo.markFailed(hash, "degrade-to-pending ingest failed");
            moveToFailedTracked(staged, hash, fileRepo);
        }
    }

    /** Move to failed/ and persist the (possibly collision-suffixed) landed filename to the ledger
     *  so the recovery sweep resolves the physical file under its real name for a retry. */
    private void moveToFailedTracked(Path staged, String hash, ConsumptionFileRepository fileRepo) {
        try {
            Path dest = mover.moveToFailed(staged);
            if (fileRepo != null && hash != null && dest != null) {
                fileRepo.updateFilename(hash, dest.getFileName().toString());
            }
        } catch (Exception e) {
            log.error("Could not move {} to failed/: {}", staged.getFileName(), e.toString());
        }
    }

    private static String stripPdf(String name) {
        return name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }
}
