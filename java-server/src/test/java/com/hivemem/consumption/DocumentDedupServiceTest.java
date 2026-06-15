package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.SeaweedFsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentDedupServiceTest {

    private DocumentDedupRepository repo;
    private AttachmentRepository attachments;
    private SeaweedFsClient seaweed;
    private DedupProperties props;
    private DocumentDedupService service;

    private final UUID target = UUID.randomUUID();
    private final UUID original = UUID.randomUUID();
    private final UUID attId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(DocumentDedupRepository.class);
        attachments = mock(AttachmentRepository.class);
        seaweed = mock(SeaweedFsClient.class);
        props = new DedupProperties();
        service = new DocumentDedupService(repo, attachments, seaweed, props);
        when(repo.findTarget(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.TargetCell(target, "Rechnung 4711 Betrag 199",
                        OffsetDateTime.parse("2026-06-01T10:00:00Z"))));
    }

    @Test
    void discardsWhenBothStagesPass() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, "Rechnung 4711 Betrag 199", 0.99)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", "thumb.jpg")));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(0);

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
        verify(seaweed).delete("orig.pdf");
        verify(seaweed).delete("thumb.jpg");
        verify(attachments).softDelete(attId);
    }

    @Test
    void keepsAttachmentBinaryWhenStillReferenced() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, "Rechnung 4711 Betrag 199", 0.99)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", null)));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(1);

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(repo).linkAndSoftDelete(eq(target), eq(original), any(), any());
        verify(seaweed, never()).delete(any());
        verify(attachments, never()).softDelete(any());
    }

    @Test
    void s3FailureStillSoftDeletesAttachment() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, "Rechnung 4711 Betrag 199", 0.99)));
        when(repo.findAttachmentKeysForCell(target)).thenReturn(Optional.of(
                new DocumentDedupRepository.AttachmentKeys(attId, "orig.pdf", null)));
        when(repo.countOtherLiveCellsForAttachment(attId, target)).thenReturn(0);
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down")).when(seaweed).delete("orig.pdf");

        Optional<UUID> result = service.findAndDiscardDuplicate(target);

        assertEquals(Optional.of(original), result);
        verify(attachments).softDelete(attId);
    }

    @Test
    void noDiscardWhenTextGateFails() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt())).thenReturn(List.of(
                new DocumentDedupRepository.Candidate(original, "Mietvertrag Wohnung Kaution", 0.99)));

        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }

    @Test
    void disabledShortCircuits() {
        props.setEnabled(false);
        assertTrue(service.findAndDiscardDuplicate(target).isEmpty());
        verify(repo, never()).findTarget(any());
    }

    @Test
    void repoErrorIsSwallowed() {
        when(repo.findSimilarOlderCandidates(eq(target), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("boom"));
        assertFalse(service.findAndDiscardDuplicate(target).isPresent());
        verify(repo, never()).linkAndSoftDelete(any(), any(), any(), any());
    }
}
