package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.summarize.CellNeedsSummaryEvent;
import com.hivemem.summarize.NeedsSummaryDecider;
import com.hivemem.write.WriteToolRepository;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentProperties props;
    private final SeaweedFsClient seaweedFs;
    private final ParserRegistry parsers;
    private final AttachmentRepository repo;
    private final WriteToolRepository writeRepo;
    private final EmbeddingClient embeddingClient;
    private final DSLContext dsl;
    private final ApplicationEventPublisher eventPublisher;
    private final KrokiClient krokiClient;
    private final ExifExtractor exifExtractor;
    private final ImageMetaRepository imageMetaRepo;

    public AttachmentService(AttachmentProperties props, SeaweedFsClient seaweedFs,
                             ParserRegistry parsers, AttachmentRepository repo,
                             WriteToolRepository writeRepo, EmbeddingClient embeddingClient,
                             DSLContext dsl,
                             ApplicationEventPublisher eventPublisher,
                             KrokiClient krokiClient,
                             ExifExtractor exifExtractor,
                             ImageMetaRepository imageMetaRepo) {
        this.props = props;
        this.seaweedFs = seaweedFs;
        this.parsers = parsers;
        this.repo = repo;
        this.writeRepo = writeRepo;
        this.embeddingClient = embeddingClient;
        this.dsl = dsl;
        this.eventPublisher = eventPublisher;
        this.krokiClient = krokiClient;
        this.exifExtractor = exifExtractor;
        this.imageMetaRepo = imageMetaRepo;
    }

    public Map<String, Object> ingest(InputStream inputStream, String originalFilename,
                                      String mimeType, String realm, String signal, String topic,
                                      UUID optionalLinkCellId, String uploadedBy) throws Exception {
        return ingest(inputStream, originalFilename, mimeType, realm, signal, topic,
                      optionalLinkCellId, uploadedBy, "pending", "attachment:");
    }

    /**
     * D3: this method used to be {@code @Transactional} end to end, which pins a pooled Hikari
     * connection across the S3 upload of the original (up to 500MB), the thumbnail upload, and
     * the embedding HTTP call (with retries) below — starving the connection pool for the
     * duration of that I/O. All of that network/parsing work now runs BEFORE any transaction
     * opens (same precompute-then-write pattern as {@code WriteToolService.kgAdd}); only the DB
     * writes (dedup re-check, insert/reactivate, addCell, tagging, link, tunnel) run inside
     * {@link WriteToolRepository#inTransaction}, so atomicity of the writes is preserved without
     * holding a connection across I/O.
     *
     * <p>Dedup TOCTOU: the initial {@code findByHash} pre-check (used only to decide whether to
     * upload to S3 at all) is re-run inside the transaction. If a concurrent ingest of the same
     * file already inserted the hash in the meantime, this call reactivates the winner's row
     * instead of inserting a duplicate — the S3 objects this call already uploaded become
     * harmless orphans (content-addressed by hash, so they're either byte-identical to what's
     * already there, or simply unreferenced; not worth an extra round trip to delete them).
     */
    public Map<String, Object> ingest(InputStream inputStream, String originalFilename,
                                      String mimeType, String realm, String signal, String topic,
                                      UUID optionalLinkCellId, String uploadedBy,
                                      String initialStatus, String sourcePrefix) throws Exception {
        if (!props.isEnabled()) throw new IllegalStateException("Attachment storage is not enabled");

        final String resolvedMimeType = MimeTypeResolver.resolve(mimeType, originalFilename);

        Path tempFile = Files.createTempFile("hivemem-upload-", null);
        try {
            // ---- Phase 1: hashing, parsing, and S3 uploads — no DB transaction held. ----

            // 1. Stream to temp + SHA-256
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(inputStream, digest)) {
                Files.copy(dis, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            final String hash = HexFormat.of().formatHex(digest.digest());

            // 2. Parse from temp file (before dedup — both paths need text)
            ParseResult parsed;
            try (InputStream tempStream = Files.newInputStream(tempFile)) {
                parsed = parsers.parse(resolvedMimeType, tempStream);
            } catch (Exception e) {
                log.warn("Parsing failed for {} ({}): {}", originalFilename, resolvedMimeType, e.getMessage());
                parsed = ParseResult.empty();
            }
            final ParseResult finalParsed = parsed;

            // 3. Extract thumbnail bytes (failure must not abort ingest)
            byte[] thumbnail = parsed.hasThumbnail() ? parsed.thumbnail() : null;
            String thumbnailMimeType = parsed.hasThumbnail() ? parsed.thumbnailMimeType() : null;

            // 4. Dedup pre-check: a plain SELECT, just to decide whether uploading to S3 is
            // needed at all. The authoritative dedup decision is re-checked inside the
            // transaction below (see the TOCTOU note on this method's Javadoc).
            Optional<Map<String, Object>> existingPreCheck = repo.findByHash(hash);
            boolean likelyNew = existingPreCheck.isEmpty();

            long sizeBytes = 0;
            String s3KeyOriginal = null;
            String s3KeyThumbnailUploaded = null;
            if (likelyNew) {
                // 5. Upload original to SeaweedFS
                sizeBytes = Files.size(tempFile);
                String ext = extensionFor(resolvedMimeType, originalFilename);
                s3KeyOriginal = hash + "." + ext;
                seaweedFs.upload(s3KeyOriginal, tempFile, resolvedMimeType);

                // Upload thumbnail; the key is persisted only when the upload succeeded, so
                // a failed upload leaves NULL (repairable) instead of a dead key that 500s.
                if (thumbnail != null) {
                    s3KeyThumbnailUploaded = uploadThumbnail(hash + "-thumb.jpg", thumbnail, thumbnailMimeType);
                }
            } else if (thumbnail != null && existingPreCheck.get().get("s3_key_thumbnail") == null) {
                // Existing row has no thumbnail yet (prior ingest's thumbnail upload failed) —
                // fill it in now, still before any transaction opens.
                s3KeyThumbnailUploaded = uploadThumbnail(hash + "-thumb.jpg", thumbnail, thumbnailMimeType);
            }
            final long finalSizeBytes = sizeBytes;
            final String finalS3KeyOriginal = s3KeyOriginal;
            final String finalS3KeyThumbnail = s3KeyThumbnailUploaded;

            // 6. Dedup re-upload: reuse the existing extraction cell's enriched content
            // (OCR / vision output) instead of re-running the expensive pipeline.
            AttachmentRepository.ExtractionCellSeed seed = existingPreCheck
                    .flatMap(row -> repo.findExtractionCellSeed(UUID.fromString((String) row.get("id"))))
                    .orElse(null);
            // Content equal to the bare filename means the prior pipeline never enriched
            // the cell (still pending / failed) — treat it as not seeded and re-run.
            String seededContent = seed != null ? seed.content() : null;
            final boolean contentSeeded = seededContent != null && !seededContent.isBlank()
                    && !seededContent.equals(originalFilename);
            final String cellContent = contentSeeded
                    ? seededContent
                    : (parsed.extractedText() != null && !parsed.extractedText().isBlank())
                        ? parsed.extractedText()
                        : (originalFilename != null ? originalFilename : "unknown file");

            // 7. Embedding HTTP call (with its own retries) — also before any transaction opens.
            List<Float> embedding;
            boolean embeddingPending = false;
            try {
                embedding = embeddingClient.encodeForCell(cellContent, null);
            } catch (com.hivemem.embedding.EmbeddingUnavailableException e) {
                log.warn("Embedding unavailable for {} — committing cell without embedding, will backfill: {}",
                        originalFilename, e.getMessage());
                embedding = null;
                embeddingPending = true;
            }
            final List<Float> finalEmbedding = embedding;
            final boolean finalEmbeddingPending = embeddingPending;

            // ---- Phase 2: all DB writes, applied atomically inside one transaction. ----
            return writeRepo.inTransaction(() -> {
                Optional<Map<String, Object>> existing = repo.findByHash(hash);
                Map<String, Object> attachmentRow;
                boolean deduplicated = existing.isPresent();

                if (deduplicated) {
                    attachmentRow = reactivateExisting(existing.get(), finalS3KeyThumbnail);
                } else {
                    // Reuse the page count the parser already determined instead of re-loading
                    // the PDF (a local, non-network read — safe to do inside the transaction).
                    Integer pageCount = finalParsed.pageCount() != null
                            ? finalParsed.pageCount()
                            : pdfPageCount(tempFile, resolvedMimeType);
                    // Upsert: a concurrent identical upload racing past the dedup check above
                    // reactivates the winner's row instead of failing with a unique violation.
                    attachmentRow = repo.insert(hash, resolvedMimeType, originalFilename, finalSizeBytes,
                            finalS3KeyOriginal, finalS3KeyThumbnail, uploadedBy, pageCount);
                }

                UUID attachmentId = UUID.fromString((String) attachmentRow.get("id"));

                Map<String, Object> cellRow = writeRepo.addCell(
                        cellContent, finalEmbedding, realm, signal, topic,
                        sourcePrefix + attachmentRow.get("id"),
                        List.of(), null, null, null, null, null,
                        initialStatus, uploadedBy, null);

                UUID cellId = UUID.fromString((String) cellRow.get("id"));
                if (finalEmbeddingPending) {
                    writeRepo.tagEmbeddingPending(cellId);
                }

                // Carry vision subtype tags over to the seeded cell so media-UI filtering
                // (subtype_photo_general etc.) still sees it without a re-described image.
                if (contentSeeded) {
                    for (String t : seed.tags()) {
                        if (t.startsWith("subtype_")) {
                            dsl.execute(
                                    "UPDATE cells SET tags = "
                                    + "CASE WHEN ? = ANY(tags) THEN tags ELSE array_append(tags, ?) END "
                                    + "WHERE id = ?", t, t, cellId);
                        }
                    }
                }

                if (finalParsed.scanLikely() && !contentSeeded) {
                    // Scan PDF: tag for OCR; OCR will revise content and trigger summarizer.
                    String key = (String) attachmentRow.get("s3_key_original");
                    writeRepo.tagOcrPending(cellId);
                    eventPublisher.publishEvent(
                            new com.hivemem.ocr.OcrRequestedEvent(cellId, attachmentId, key));
                } else if (resolvedMimeType != null && resolvedMimeType.startsWith("image/")) {
                    if (!contentSeeded) {
                        String key = (String) attachmentRow.get("s3_key_original");
                        writeRepo.tagVisionPending(cellId);
                        eventPublisher.publishEvent(
                                new VisionDescriptionRequestedEvent(attachmentId, cellId, key, resolvedMimeType));
                    }
                    extractAndStoreImageMeta(tempFile, attachmentId);
                } else if (krokiClient.supports(resolvedMimeType) && attachmentRow.get("s3_key_thumbnail") == null) {
                    String fileHash = (String) attachmentRow.get("file_hash");
                    writeRepo.tagKrokiPending(cellId);
                    eventPublisher.publishEvent(
                            new ThumbnailRequestedEvent(attachmentId, cellId, fileHash, resolvedMimeType, cellContent));
                } else if (NeedsSummaryDecider.needsSummary(cellContent, null)) {
                    writeRepo.tagNeedsSummary(cellId);
                    eventPublisher.publishEvent(new CellNeedsSummaryEvent(cellId));
                }

                // 7. Link cell ↔ attachment
                repo.linkExtractionCell(attachmentId, cellId);

                // 8. Tunnel to existing cell if provided
                if (optionalLinkCellId != null) {
                    boolean cellExists = dsl.fetchOne(
                            "SELECT 1 FROM cells WHERE id = ? AND valid_until IS NULL",
                            optionalLinkCellId) != null;
                    if (!cellExists) {
                        throw new IllegalArgumentException("Cell not found: " + optionalLinkCellId);
                    }
                    writeRepo.addTunnel(cellId, optionalLinkCellId, "related_to", null, "committed", uploadedBy);
                }

                // Build response
                Map<String, Object> result = new LinkedHashMap<>(attachmentRow);
                result.put("cell_id", cellId.toString());
                result.put("has_thumbnail", attachmentRow.get("s3_key_thumbnail") != null);
                result.put("deduplicated", deduplicated);
                return result;
            });

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public InputStream downloadOriginal(UUID id) {
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + id));
        return seaweedFs.download((String) row.get("s3_key_original"));
    }

    public InputStream downloadThumbnail(UUID id) {
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + id));
        String key = (String) row.get("s3_key_thumbnail");
        if (key == null) throw new NoSuchElementException("No thumbnail for attachment: " + id);
        return seaweedFs.download(key);
    }

    /** Ranged download of the original (HTTP Range semantics, end inclusive). */
    public InputStream downloadRange(UUID id, long start, long endInclusive) {
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + id));
        return seaweedFs.downloadRange((String) row.get("s3_key_original"), start, endInclusive);
    }

    /**
     * Dedup path: clears deleted_at, and adopts a freshly-uploaded thumbnail key when the
     * existing row had none. The upload itself (if any) already happened in Phase 1, before this
     * transaction opened — D3 requires no network calls (S3 or otherwise) inside the write
     * transaction, so this method is pure DB I/O.
     */
    private Map<String, Object> reactivateExisting(Map<String, Object> existingRow, String freshlyUploadedThumbnailKey) {
        UUID existingId = UUID.fromString((String) existingRow.get("id"));
        String s3KeyThumbnail = (String) existingRow.get("s3_key_thumbnail");
        if (s3KeyThumbnail == null && freshlyUploadedThumbnailKey != null) {
            s3KeyThumbnail = freshlyUploadedThumbnailKey;
        }
        return repo.reactivate(existingId, s3KeyThumbnail);
    }

    private void extractAndStoreImageMeta(Path tempFile, UUID attachmentId) {
        try {
            // Skip if metadata already exists (dedup/re-ingest path is idempotent).
            if (imageMetaRepo.findByAttachmentId(attachmentId).isPresent()) return;
            byte[] bytes = Files.readAllBytes(tempFile);
            ExifData exif = exifExtractor.extract(bytes);
            boolean hasGps = exif.gpsLat() != null && exif.gpsLon() != null;
            String geocodeStatus = hasGps ? "pending" : "none";
            imageMetaRepo.upsert(attachmentId, exif, geocodeStatus);
            if (hasGps) {
                eventPublisher.publishEvent(
                        new GeocodeRequestedEvent(attachmentId, exif.gpsLat(), exif.gpsLon()));
            }
        } catch (Exception e) {
            log.warn("Image EXIF extraction failed for {}: {}", attachmentId, e.getMessage());
        }
    }

    /**
     * @return the key when the upload succeeded, {@code null} when it failed — so callers
     *         never persist a key that references a nonexistent S3 object.
     */
    private String uploadThumbnail(String key, byte[] bytes, String mimeType) {
        try {
            seaweedFs.uploadBytes(key, bytes, mimeType != null ? mimeType : "image/jpeg");
            return key;
        } catch (Exception e) {
            log.warn("Thumbnail upload failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    static Integer pdfPageCount(Path file, String mimeType) {
        if (!"application/pdf".equals(mimeType)) return null;
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            log.warn("Could not determine PDF page count for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private String extensionFor(String mimeType, String filename) {
        String ext = switch (mimeType) {
            case "application/pdf"  -> "pdf";
            case "image/jpeg"       -> "jpg";
            case "image/png"        -> "png";
            case "image/gif"        -> "gif";
            case "image/webp"       -> "webp";
            case "message/rfc822"   -> "eml";
            default -> null;
        };
        if (ext != null) return ext;
        if (filename != null && filename.contains("."))
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return "bin";
    }
}
