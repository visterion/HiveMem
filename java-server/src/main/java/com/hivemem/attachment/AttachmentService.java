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
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Map<String, Object> ingest(InputStream inputStream, String originalFilename,
                                      String mimeType, String realm, String signal, String topic,
                                      UUID optionalLinkCellId, String uploadedBy) throws Exception {
        return ingest(inputStream, originalFilename, mimeType, realm, signal, topic,
                      optionalLinkCellId, uploadedBy, "pending", "attachment:");
    }

    @Transactional
    public Map<String, Object> ingest(InputStream inputStream, String originalFilename,
                                      String mimeType, String realm, String signal, String topic,
                                      UUID optionalLinkCellId, String uploadedBy,
                                      String initialStatus, String sourcePrefix) throws Exception {
        if (!props.isEnabled()) throw new IllegalStateException("Attachment storage is not enabled");

        mimeType = MimeTypeResolver.resolve(mimeType, originalFilename);

        Path tempFile = Files.createTempFile("hivemem-upload-", null);
        try {
            // 1. Stream to temp + SHA-256
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(inputStream, digest)) {
                Files.copy(dis, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(digest.digest());

            // 2. Parse from temp file (before dedup — both paths need text)
            ParseResult parsed;
            try (InputStream tempStream = Files.newInputStream(tempFile)) {
                parsed = parsers.parse(mimeType, tempStream);
            } catch (Exception e) {
                log.warn("Parsing failed for {} ({}): {}", originalFilename, mimeType, e.getMessage());
                parsed = ParseResult.empty();
            }

            // 3. Extract thumbnail bytes (failure must not abort ingest)
            byte[] thumbnail = parsed.hasThumbnail() ? parsed.thumbnail() : null;
            String thumbnailMimeType = parsed.hasThumbnail() ? parsed.thumbnailMimeType() : null;

            // 4. Dedup check
            Optional<Map<String, Object>> existing = repo.findByHash(hash);
            Map<String, Object> attachmentRow;
            boolean deduplicated = existing.isPresent();

            if (existing.isPresent()) {
                attachmentRow = reactivateExisting(existing.get(), hash, thumbnail, thumbnailMimeType);
            } else {
                // 5. Upload original to SeaweedFS
                long sizeBytes = Files.size(tempFile);
                String ext = extensionFor(mimeType, originalFilename);
                String s3KeyOriginal = hash + "." + ext;
                seaweedFs.upload(s3KeyOriginal, tempFile, mimeType);

                // Upload thumbnail; the key is persisted only when the upload succeeded, so
                // a failed upload leaves NULL (repairable) instead of a dead key that 500s.
                String s3KeyThumbnail = null;
                if (thumbnail != null) {
                    s3KeyThumbnail = uploadThumbnail(hash + "-thumb.jpg", thumbnail, thumbnailMimeType);
                }

                // Reuse the page count the parser already determined instead of re-loading the PDF.
                Integer pageCount = parsed.pageCount() != null
                        ? parsed.pageCount()
                        : pdfPageCount(tempFile, mimeType);
                // Upsert: a concurrent identical upload racing past the dedup check above
                // reactivates the winner's row instead of failing with a unique violation.
                attachmentRow = repo.insert(hash, mimeType, originalFilename, sizeBytes,
                        s3KeyOriginal, s3KeyThumbnail, uploadedBy, pageCount);
            }

            // 6. Create extraction Cell.
            // Dedup re-upload: reuse the existing extraction cell's enriched content
            // (OCR / vision output) instead of re-running the expensive pipeline.
            AttachmentRepository.ExtractionCellSeed seed = null;
            if (deduplicated) {
                seed = repo.findExtractionCellSeed(
                        UUID.fromString((String) attachmentRow.get("id"))).orElse(null);
            }
            // Content equal to the bare filename means the prior pipeline never enriched
            // the cell (still pending / failed) — treat it as not seeded and re-run.
            String seededContent = seed != null ? seed.content() : null;
            boolean contentSeeded = seededContent != null && !seededContent.isBlank()
                    && !seededContent.equals(originalFilename);
            String cellContent = contentSeeded
                    ? seededContent
                    : (parsed.extractedText() != null && !parsed.extractedText().isBlank())
                        ? parsed.extractedText()
                        : (originalFilename != null ? originalFilename : "unknown file");
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

            Map<String, Object> cellRow = writeRepo.addCell(
                    cellContent, embedding, realm, signal, topic,
                    sourcePrefix + attachmentRow.get("id"),
                    List.of(), null, null, null, null, null,
                    initialStatus, uploadedBy, null);

            UUID cellId = UUID.fromString((String) cellRow.get("id"));
            if (embeddingPending) {
                writeRepo.tagEmbeddingPending(cellId);
            }
            UUID attachmentId = UUID.fromString((String) attachmentRow.get("id"));

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

            if (parsed.scanLikely() && !contentSeeded) {
                // Scan PDF: tag for OCR; OCR will revise content and trigger summarizer.
                String key = (String) attachmentRow.get("s3_key_original");
                writeRepo.tagOcrPending(cellId);
                eventPublisher.publishEvent(
                        new com.hivemem.ocr.OcrRequestedEvent(cellId, attachmentId, key));
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                if (!contentSeeded) {
                    String key = (String) attachmentRow.get("s3_key_original");
                    writeRepo.tagVisionPending(cellId);
                    eventPublisher.publishEvent(
                            new VisionDescriptionRequestedEvent(attachmentId, cellId, key, mimeType));
                }
                extractAndStoreImageMeta(tempFile, attachmentId);
            } else if (krokiClient.supports(mimeType) && attachmentRow.get("s3_key_thumbnail") == null) {
                String fileHash = (String) attachmentRow.get("file_hash");
                writeRepo.tagKrokiPending(cellId);
                eventPublisher.publishEvent(
                        new ThumbnailRequestedEvent(attachmentId, cellId, fileHash, mimeType, cellContent));
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
     * Dedup path: clears deleted_at, uploads a thumbnail only when the existing row has
     * none (and the key is persisted only when that upload succeeded — see uploadThumbnail).
     */
    private Map<String, Object> reactivateExisting(Map<String, Object> existingRow, String hash,
                                                   byte[] thumbnail, String thumbnailMimeType) {
        UUID existingId = UUID.fromString((String) existingRow.get("id"));
        String s3KeyThumbnail = (String) existingRow.get("s3_key_thumbnail");
        if (thumbnail != null && s3KeyThumbnail == null) {
            s3KeyThumbnail = uploadThumbnail(hash + "-thumb.jpg", thumbnail, thumbnailMimeType);
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
