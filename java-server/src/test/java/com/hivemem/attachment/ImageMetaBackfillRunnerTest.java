package com.hivemem.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImageMetaBackfillRunnerTest {

    private final ImageMetaRepository repo = mock(ImageMetaRepository.class);
    private final SeaweedFsClient seaweedFs = mock(SeaweedFsClient.class);
    private final ExifExtractor exif = mock(ExifExtractor.class);
    private final AttachmentRepository attRepo = mock(AttachmentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private ImageMetaBackfillRunner runner() {
        return new ImageMetaBackfillRunner(repo, seaweedFs, exif, attRepo, events);
    }

    @Test
    void backfillsMissingImageWithGpsAndFiresGeocode() {
        UUID att = UUID.randomUUID();
        when(repo.findImageAttachmentsWithoutMeta()).thenReturn(List.of(att));
        when(attRepo.findById(att)).thenReturn(java.util.Optional.of(
                java.util.Map.of("s3_key_original", "orig/p.jpg")));
        when(seaweedFs.downloadBytes("orig/p.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(exif.extract(any())).thenReturn(
                new ExifData(120, 80, null, "Apple", "iPhone", 49.48, 8.46, 1));

        runner().backfill();

        verify(repo).upsert(eq(att), any(ExifData.class), eq("pending"));
        verify(events).publishEvent(any(GeocodeRequestedEvent.class));
    }

    @Test
    void noMissingImagesDoesNothing() {
        when(repo.findImageAttachmentsWithoutMeta()).thenReturn(List.of());
        runner().backfill();
        verifyNoInteractions(seaweedFs);
        verify(repo, never()).upsert(any(), any(), any());
    }
}
