package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.ocr.OcrProperties;
import com.hivemem.ocr.PdfPageRasterizer;
import com.hivemem.ocr.TesseractRunner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionService implements SeparationApplier {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionService.class);

    private static final Pattern PDF = Pattern.compile(".*\\.pdf$", Pattern.CASE_INSENSITIVE);

    private final ConsumptionProperties props;
    private final AttachmentService attachments;
    private final ConsumptionFileMover mover;

    // Separation collaborators
    private final PdfPageRasterizer rasterizer;
    private final TesseractRunner tesseract;
    private final OcrProperties ocrProps;
    private final PageDigestBuilder digestBuilder;
    private final BatchSplitter splitter;
    private final SeaweedFsClient seaweed;
    private final SeparationJobRepository jobs;
    private final VistierieSeparationClient separationClient; // may be null (queen disabled)
    private final ReassemblyOrchestrator reassembly;          // may be null (queen disabled)

    public ConsumptionService(ConsumptionProperties props, AttachmentService attachments,
                              OcrProperties ocrProps, SeaweedFsClient seaweed,
                              SeparationJobRepository jobs,
                              ObjectProvider<VistierieSeparationClient> separationClientProvider,
                              ObjectProvider<VisionMultiClient> visionMultiProvider) {
        this.props = props;
        this.attachments = attachments;
        this.mover = new ConsumptionFileMover(Path.of(props.getDir()));
        this.ocrProps = ocrProps;
        this.seaweed = seaweed;
        this.jobs = jobs;
        this.rasterizer = new PdfPageRasterizer();
        this.tesseract = new TesseractRunner(ocrProps.getTesseractPath());
        this.digestBuilder = new PageDigestBuilder();
        this.splitter = new BatchSplitter();
        this.separationClient = separationClientProvider.getIfAvailable();
        VisionMultiClient visionMultiClient = visionMultiProvider.getIfAvailable();
        this.reassembly = (visionMultiClient != null)
                ? new ReassemblyOrchestrator(props, rasterizer, new PageGrouper(visionMultiClient, props),
                        new PageReassembler(props), splitter, attachments, mover)
                : null;
    }

    /** Process one already-staged file (the watcher moved it into processing/ first, which makes it
     *  invisible to the non-recursive poll → exactly-once). PDFs with >1 page (and queen enabled) go
     *  through batch separation; everything else is a single committed document. Runs on the consumption
     *  executor, never the @Scheduled poll thread. Location-agnostic: it reads, processes, and moves the
     *  file to processed/ or failed/. */
    public void processStaged(Path staged) {
        String filename = staged.getFileName().toString();
        byte[] bytes;
        int pageCount;
        boolean splittable;
        try {
            bytes = Files.readAllBytes(staged);  // read fully; stream closed before move
            pageCount = PDF.matcher(filename).matches() ? pdfPageCount(bytes) : 1;
            splittable = separationClient != null
                    && PDF.matcher(filename).matches()
                    && pageCount > 1;
        } catch (Exception e) {
            log.warn("Consumption read failed for {}: {}", filename, e.toString());
            tryMoveFailed(staged);
            return;
        }
        if (props.isReassemblyEnabled() && reassembly != null
                && PDF.matcher(filename).matches() && pageCount > 1) {
            // Content-based reassembly takes precedence over contiguous separation when enabled.
            // reassemble() never throws: it owns the staged file's lifecycle and degrades on error.
            reassembly.reassemble(staged, bytes, pageCount);
        } else if (splittable) {
            // The separation branch owns the staged file's lifecycle (moves to failed/ on its own
            // errors, leaves it for reconcile on dispatch failure). It must NOT fall through.
            separateStaged(staged, filename, bytes, pageCount);
        } else {
            try {
                ingestSingle(staged, filename, bytes);
            } catch (Exception e) {
                log.warn("Consumption ingest failed for {}: {}", filename, e.toString());
                tryMoveFailed(staged);
            }
        }
    }

    private int pdfPageCount(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    private void ingestSingle(Path file, String filename, byte[] bytes) throws Exception {
        String mime = URLConnection.guessContentTypeFromName(filename);
        if (mime == null) mime = "application/octet-stream";
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            attachments.ingest(in, filename, mime, props.getRealm(), null, null, null,
                    "consumption", "committed", "consumption:");
        }
        mover.moveToProcessed(file);
        log.info("Consumed {} -> committed cell in realm {}", filename, props.getRealm());
    }

    /**
     * Multi-page separation path for an ALREADY-staged file (the watcher moved it to {@code processing/}
     * before submitting, which keeps the non-recursive poll from re-scanning it). OCR/upload/create errors
     * route the staged file to {@code failed/}; a dispatch failure leaves the job {@code awaiting} + the
     * file in {@code processing/} for the reconcile sweep to degrade later. It never throws.
     */
    private void separateStaged(Path staged, String filename, byte[] bytes, int realPageCount) {
        // BUG 3 guard: rasterizer caps at maxPages, which would silently lump trailing pages into the
        // last document. Reject explicitly (logged) so operators re-scan in smaller batches or raise the cap.
        if (realPageCount > props.getMaxPages()) {
            log.warn("Batch {} has {} pages > max-pages {}; routing to failed/ (re-scan in smaller batches "
                            + "or raise hivemem.consumption.max-pages)",
                    filename, realPageCount, props.getMaxPages());
            tryMoveFailed(staged);
            return;
        }

        UUID correlationId = UUID.randomUUID();
        boolean jobCreated = false;
        try {
            List<byte[]> pages = rasterizer.rasterize(bytes, ocrProps.getRenderDpi(), props.getMaxPages());
            List<PageDigest> digests = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                String text;
                try { text = tesseract.ocr(pages.get(i), ocrProps.getLanguages(), ocrProps.getCallTimeoutSeconds()); }
                catch (Exception ocrErr) { text = ""; }
                digests.add(digestBuilder.build(i + 1, text));
            }
            String s3Key = "consumption/batch-" + correlationId + ".pdf";
            seaweed.uploadBytes(s3Key, bytes, "application/pdf");
            // Create the job (status 'awaiting') BEFORE dispatch so the webhook always finds it.
            jobs.create(correlationId, s3Key, filename, staged.toAbsolutePath().toString(),
                    pages.size(), props.getRealm());
            jobCreated = true;

            try {
                String runId = separationClient.dispatch(correlationId, digests);
                // Store the run id so the completion callback (which carries no correlation id) can
                // match this job deterministically. If null, the job stays correlatable only via the
                // reconcile sweep's degrade path.
                if (runId != null) jobs.attachRunId(correlationId, runId);
            } catch (Exception dispatchErr) {
                // BUG 2: leave the job 'awaiting' and the file in processing/ with S3 populated.
                // SeparationReconcileSweep will degrade it later. Nothing is lost.
                log.warn("Dispatch failed for separation job {} ({}): {} - leaving awaiting for reconcile",
                        correlationId, filename, dispatchErr.toString());
                return;
            }
            log.info("Dispatched separation job {} ({} pages) for {}", correlationId, pages.size(), filename);
        } catch (Exception e) {
            // OCR/upload/create failure (before a successful dispatch): the batch cannot proceed.
            log.warn("Separation prep failed for {}: {}", filename, e.toString());
            if (jobCreated) jobs.markFailed(correlationId);
            tryMoveFailed(staged);
        }
    }

    @Override
    public void apply(SeparationResult result) {
        String runId = result.runId();
        // A non-'done' run or a missing output means Vistierie could not produce boundaries. Leave the
        // job 'awaiting' so SeparationReconcileSweep degrades it into a single pending document after the
        // stale window — never lose the batch by marking it failed here.
        if (!"done".equals(result.status()) || result.output() == null) {
            log.warn("Separation run {} not usable (status={}); leaving awaiting for reconcile",
                    runId, result.status());
            return;
        }
        var jobOpt = jobs.findAwaitingByRunId(runId);
        if (jobOpt.isEmpty()) {
            log.info("No awaiting job for run {} (already done?)", runId);
            return;
        }
        SeparationJobRepository.Job job = jobOpt.get();
        try {
            byte[] pdf = seaweed.downloadBytes(job.s3Key());
            int total = job.pageCount();
            // An empty boundary list is a valid result: the whole stream is one document.
            List<SeparationResult.Boundary> boundaries =
                    result.output().boundaries() == null ? List.of() : result.output().boundaries();
            // Keep boundaries and parts aligned: derive the SAME valid, distinct, sorted cut list
            // that BatchSplitter uses, carrying each cut's confidence alongside it.
            TreeMap<Integer, Double> validCuts = new TreeMap<>();
            for (SeparationResult.Boundary b : boundaries) {
                if (b.afterPage() >= 1 && b.afterPage() < total) {
                    validCuts.merge(b.afterPage(), b.confidence(), Math::max); // dedupe, keep max conf
                }
            }
            List<Integer> cuts = new ArrayList<>(validCuts.keySet());
            List<Double> cutConfidences = new ArrayList<>(validCuts.values());
            List<byte[]> parts = splitter.split(pdf, cuts);   // parts.size() == cuts.size() + 1
            double threshold = props.getConfidenceThreshold();
            for (int k = 0; k < parts.size(); k++) {
                String status = "committed";
                if (k > 0) {
                    double conf = cutConfidences.get(k - 1);
                    status = conf >= threshold ? "committed" : "pending";
                }
                String partName = stripPdf(job.originalName()) + "-" + (k + 1) + ".pdf";
                try (InputStream in = new ByteArrayInputStream(parts.get(k))) {
                    attachments.ingest(in, partName, "application/pdf", job.realm(),
                            null, null, null, "consumption", status, "consumption:");
                }
            }
            // Mark done BEFORE the move: sub-docs are already ingested, so a move failure must not
            // flip the job to 'failed'. The source path is now the processing/ staged path.
            jobs.markDone(job.correlationId());
            tryMoveProcessedTolerant(Path.of(job.sourcePath()));
            log.info("Applied separation run {} (job {}): {} documents",
                    runId, job.correlationId(), parts.size());
        } catch (Exception e) {
            log.warn("apply separation run {} (job {}) failed: {}", runId, job.correlationId(), e.toString());
            jobs.markFailed(job.correlationId());
            tryMoveFailed(Path.of(job.sourcePath()));
        }
    }

    private static String stripPdf(String name) {
        return name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    private void tryMoveFailed(Path file) {
        try { mover.moveToFailed(file); }
        catch (IOException io) { log.error("Could not move {} to failed/: {}", file, io.toString()); }
    }

    /** Move a (staged) source to processed/ but never fail the caller on a move error — the work is done. */
    private void tryMoveProcessedTolerant(Path file) {
        try { mover.moveToProcessed(file); }
        catch (Exception io) { log.warn("Could not move {} to processed/: {}", file, io.toString()); }
    }
}
