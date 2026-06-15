package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.write.WriteToolRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Content-based dedup for scanned documents. Runs after OCR has populated a cell's text + embedding.
 * Two stages: pgvector cosine recall (DedupProperties.recallThreshold) then a normalized-text Jaccard
 * gate (textThreshold). On a confirmed duplicate the freshly-OCR'd cell is discarded (soft-deleted),
 * its attachment binary removed if unreferenced, and a duplicate_of tunnel points to the original.
 * Best-effort: any failure logs and leaves the document untouched.
 */
@Service
public class DocumentDedupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDedupService.class);
    private static final String DEDUP_ACTOR = "system-dedup";

    private final DocumentDedupRepository repo;
    private final AttachmentRepository attachments;
    private final SeaweedFsClient seaweed;
    private final WriteToolRepository writeRepo;
    private final DedupProperties props;

    public DocumentDedupService(DocumentDedupRepository repo, AttachmentRepository attachments,
                                SeaweedFsClient seaweed, WriteToolRepository writeRepo,
                                DedupProperties props) {
        this.repo = repo;
        this.attachments = attachments;
        this.seaweed = seaweed;
        this.writeRepo = writeRepo;
        this.props = props;
    }

    /** @return the original cell id if {@code cellId} was a duplicate and got discarded, else empty. */
    public Optional<UUID> findAndDiscardDuplicate(UUID cellId) {
        if (!props.isEnabled()) return Optional.empty();
        try {
            Optional<DocumentDedupRepository.TargetCell> targetOpt = repo.findTarget(cellId);
            if (targetOpt.isEmpty()) return Optional.empty();
            String targetText = targetOpt.get().content();
            if (targetText == null || targetText.isBlank()) return Optional.empty();

            List<DocumentDedupRepository.Candidate> candidates =
                    repo.findSimilarOlderCandidates(cellId, props.getRecallThreshold(), props.getCandidateK());
            for (DocumentDedupRepository.Candidate c : candidates) {
                if (TextSimilarity.similarity(targetText, c.content()) >= props.getTextThreshold()) {
                    discard(cellId, c.id());
                    return Optional.of(c.id());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Dedup check failed for cell {} (keeping it): {}", cellId, e.toString());
            return Optional.empty();
        }
    }

    private void discard(UUID duplicateCellId, UUID originalCellId) {
        // Snapshot attachment keys before soft-delete changes the live-reference count.
        Optional<DocumentDedupRepository.AttachmentKeys> keys =
                repo.findAttachmentKeysForCell(duplicateCellId);

        repo.softDeleteCell(duplicateCellId);
        writeRepo.addTunnel(duplicateCellId, originalCellId, "duplicate_of",
                "auto-dedup: re-scanned content of " + originalCellId, "committed", DEDUP_ACTOR);

        keys.ifPresent(k -> {
            int others = repo.countOtherLiveCellsForAttachment(k.attachmentId(), duplicateCellId);
            if (others == 0) {
                try {
                    seaweed.delete(k.s3KeyOriginal());
                    if (k.s3KeyThumbnail() != null) seaweed.delete(k.s3KeyThumbnail());
                } catch (Exception e) {
                    log.warn("Dedup: S3 cleanup failed for attachment {}: {}", k.attachmentId(), e.toString());
                }
                attachments.softDelete(k.attachmentId());
            }
        });
        log.info("Dedup: discarded duplicate cell {} (re-scan of {})", duplicateCellId, originalCellId);
    }
}
