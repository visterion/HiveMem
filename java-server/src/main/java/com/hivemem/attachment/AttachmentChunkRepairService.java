package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One-time (idempotent) repair sweep for attachments whose stored S3 object still
 * carries the {@code aws-chunked} SigV4 streaming framing (see {@link AwsChunkedDecoder}).
 *
 * <p>For every live attachment it inspects the original and thumbnail objects; any
 * that are framed are decoded losslessly and re-uploaded. With chunked encoding now
 * disabled on the client the re-upload is stored cleanly. Re-running is safe: clean
 * objects are detected and skipped.
 */
@Service
public class AttachmentChunkRepairService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentChunkRepairService.class);

    private final AttachmentRepository repo;
    private final SeaweedFsClient s3;

    public AttachmentChunkRepairService(AttachmentRepository repo, SeaweedFsClient s3) {
        this.repo = repo;
        this.s3 = s3;
    }

    public record Result(int scanned, int repairedOriginals, int repairedThumbnails, int failed) {}

    public Result repairAll() {
        int scanned = 0, originals = 0, thumbnails = 0, failed = 0;
        for (AttachmentRepository.StorageKeys a : repo.findAllStorageKeys()) {
            scanned++;
            try {
                if (repairKey(a.s3KeyOriginal(), a.mimeType())) originals++;
            } catch (Exception e) {
                failed++;
                log.warn("chunk-repair: original failed id={} key={}: {}", a.id(), a.s3KeyOriginal(), e.toString());
            }
            try {
                if (repairKey(a.s3KeyThumbnail(), "image/jpeg")) thumbnails++;
            } catch (Exception e) {
                failed++;
                log.warn("chunk-repair: thumbnail failed id={} key={}: {}", a.id(), a.s3KeyThumbnail(), e.toString());
            }
        }
        log.info("chunk-repair done: scanned={} originals={} thumbnails={} failed={}",
                scanned, originals, thumbnails, failed);
        return new Result(scanned, originals, thumbnails, failed);
    }

    /** Returns true if the object was framed and got rewritten. */
    private boolean repairKey(String key, String contentType) {
        if (key == null || key.isBlank()) return false;
        byte[] stored = s3.downloadBytes(key);
        if (!AwsChunkedDecoder.isChunked(stored)) return false;
        byte[] clean = AwsChunkedDecoder.decode(stored);
        s3.uploadBytes(key, clean, contentType);
        log.info("chunk-repair: rewrote key={} {} -> {} bytes", key, stored.length, clean.length);
        return true;
    }
}
