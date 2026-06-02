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

    public ConsumptionService(ConsumptionProperties props, AttachmentService attachments,
                              OcrProperties ocrProps, SeaweedFsClient seaweed,
                              SeparationJobRepository jobs,
                              ObjectProvider<VistierieSeparationClient> separationClientProvider) {
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
    }

    /** Ingest one stable file. PDFs with >1 page (and queen enabled) go through batch separation;
     *  everything else is a single committed document. */
    public void ingestFile(Path file) {
        String filename = file.getFileName().toString();
        try {
            byte[] bytes = Files.readAllBytes(file);  // read fully; stream closed before move
            boolean splittable = separationClient != null
                    && PDF.matcher(filename).matches()
                    && pdfPageCount(bytes) > 1;
            if (splittable) {
                dispatchForSeparation(file, filename, bytes);   // async via Vistierie; leaves file in place
            } else {
                ingestSingle(file, filename, bytes);            // M1 behavior
            }
        } catch (Exception e) {
            log.warn("Consumption ingest failed for {}: {}", filename, e.toString());
            tryMoveFailed(file);
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

    private void dispatchForSeparation(Path file, String filename, byte[] bytes) throws Exception {
        List<byte[]> pages = rasterizer.rasterize(bytes, ocrProps.getRenderDpi(), props.getMaxPages());
        List<PageDigest> digests = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String text;
            try { text = tesseract.ocr(pages.get(i), ocrProps.getLanguages(), ocrProps.getCallTimeoutSeconds()); }
            catch (Exception ocrErr) { text = ""; }
            digests.add(digestBuilder.build(i + 1, text));
        }
        UUID correlationId = UUID.randomUUID();
        String s3Key = "consumption/batch-" + correlationId + ".pdf";
        seaweed.uploadBytes(s3Key, bytes, "application/pdf");
        jobs.create(correlationId, s3Key, filename, file.toAbsolutePath().toString(),
                pages.size(), props.getRealm());
        separationClient.dispatch(correlationId, digests);
        log.info("Dispatched separation job {} ({} pages) for {}", correlationId, pages.size(), filename);
    }

    @Override
    public void apply(SeparationResult result) {
        var jobOpt = jobs.findAwaiting(result.correlationId());
        if (jobOpt.isEmpty()) {
            log.info("No awaiting job for correlation {} (already done?)", result.correlationId());
            return;
        }
        SeparationJobRepository.Job job = jobOpt.get();
        try {
            byte[] pdf = seaweed.downloadBytes(job.s3Key());
            int total = job.pageCount();
            // Keep boundaries and parts aligned: derive the SAME valid, distinct, sorted cut list
            // that BatchSplitter uses, carrying each cut's confidence alongside it.
            TreeMap<Integer, Double> validCuts = new TreeMap<>();
            for (SeparationResult.Boundary b : result.boundaries()) {
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
            jobs.markDone(result.correlationId());
            mover.moveToProcessed(Path.of(job.sourcePath()));
            log.info("Applied separation {}: {} documents", result.correlationId(), parts.size());
        } catch (Exception e) {
            log.warn("apply separation {} failed: {}", result.correlationId(), e.toString());
            jobs.markFailed(result.correlationId());
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
}
