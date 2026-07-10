package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingUnavailableException;
import com.hivemem.write.WriteToolRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentServiceUnitTest {

    private final AttachmentProperties props = new AttachmentProperties();
    private final SeaweedFsClient seaweedFs = mock(SeaweedFsClient.class);
    private final ParserRegistry parsers = mock(ParserRegistry.class);
    private final AttachmentRepository repo = mock(AttachmentRepository.class);
    private final WriteToolRepository writeRepo = mock(WriteToolRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final DSLContext dsl = mock(DSLContext.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final KrokiClient krokiClient = mock(KrokiClient.class);
    private final ExifExtractor exifExtractor = mock(ExifExtractor.class);
    private final ImageMetaRepository imageMetaRepo = mock(ImageMetaRepository.class);

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(props, seaweedFs, parsers, repo, writeRepo,
                embeddingClient, dsl, events, krokiClient, exifExtractor, imageMetaRepo);
    }

    @Test
    void ingestThrowsWhenStorageDisabled() {
        props.setEnabled(false);
        InputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.ingest(in, "x.txt", "text/plain", "r", "s", "t", null, "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attachment storage is not enabled");
    }

    @Test
    void downloadOriginalThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadOriginal(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void downloadThumbnailThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadThumbnail(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadThumbnailThrowsWhenNoThumbnailKey() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_original", "key.bin");
        row.put("s3_key_thumbnail", null);
        when(repo.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.downloadThumbnail(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No thumbnail");
    }

    @Test
    void downloadOriginalReturnsStream() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_original", "key.bin");
        when(repo.findById(id)).thenReturn(Optional.of(row));
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2});
        when(seaweedFs.download("key.bin")).thenReturn(stream);

        assertThat(service.downloadOriginal(id)).isSameAs(stream);
    }

    @Test
    void downloadThumbnailReturnsStream() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_thumbnail", "thumb.jpg");
        when(repo.findById(id)).thenReturn(Optional.of(row));
        InputStream stream = new ByteArrayInputStream(new byte[]{9});
        when(seaweedFs.download("thumb.jpg")).thenReturn(stream);

        assertThat(service.downloadThumbnail(id)).isSameAs(stream);
    }

    @Test
    void ingestCommitsWithoutEmbeddingWhenServiceUnavailable() throws Exception {
        props.setEnabled(true);

        when(parsers.parse(eq("text/plain"), any()))
                .thenReturn(ParseResult.empty());
        when(embeddingClient.encodeForCell(anyString(), any()))
                .thenThrow(new EmbeddingUnavailableException("service down", null));

        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        when(repo.findByHash(anyString())).thenReturn(Optional.empty());
        Map<String, Object> attRow = new LinkedHashMap<>();
        attRow.put("id", attId.toString());
        attRow.put("file_hash", "h");
        attRow.put("s3_key_original", "orig/f.txt");
        attRow.put("s3_key_thumbnail", null);
        when(repo.insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString(),
                org.mockito.ArgumentMatchers.nullable(Integer.class))).thenReturn(attRow);
        Map<String, Object> cellRow = new LinkedHashMap<>();
        cellRow.put("id", cellId.toString());
        when(writeRepo.addCell(anyString(), isNull(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(cellRow);

        InputStream in = new ByteArrayInputStream("hello".getBytes());
        service.ingest(in, "f.txt", "text/plain", "work", "facts", "notes", null, "user");

        // addCell must have been called with a null embedding
        ArgumentCaptor<List> embeddingCaptor = ArgumentCaptor.forClass(List.class);
        verify(writeRepo).addCell(anyString(), isNull(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any());

        // tagEmbeddingPending must have been called with the cell's UUID
        verify(writeRepo).tagEmbeddingPending(cellId);
    }

    @Test
    void thumbnailUploadFailureLeavesNullThumbnailKey() throws Exception {
        props.setEnabled(true);

        when(parsers.parse(eq("application/pdf"), any()))
                .thenReturn(ParseResult.withThumbnailAndScan(null, new byte[]{1, 2, 3}, false, 1));
        when(embeddingClient.encodeForCell(anyString(), any())).thenReturn(java.util.List.of(0.1f));
        when(repo.findByHash(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down"))
                .when(seaweedFs).uploadBytes(anyString(), any(), anyString());

        Map<String, Object> attRow = new LinkedHashMap<>();
        attRow.put("id", UUID.randomUUID().toString());
        attRow.put("file_hash", "h");
        attRow.put("s3_key_original", "orig/f.pdf");
        attRow.put("s3_key_thumbnail", null);
        when(repo.insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString(),
                org.mockito.ArgumentMatchers.nullable(Integer.class))).thenReturn(attRow);
        Map<String, Object> cellRow = new LinkedHashMap<>();
        cellRow.put("id", UUID.randomUUID().toString());
        when(writeRepo.addCell(anyString(), any(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(cellRow);

        InputStream in = new ByteArrayInputStream(new byte[]{5});
        service.ingest(in, "f.pdf", "application/pdf", "work", "facts", "docs", null, "user");

        // The failed thumbnail upload must persist a NULL key (repairable by backfill),
        // never a key that references a nonexistent S3 object (permanent 500s).
        verify(repo).insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), isNull(), anyString(), org.mockito.ArgumentMatchers.nullable(Integer.class));
    }

    @Test
    void dedupReuploadSeedsExistingContentAndSkipsOcr() throws Exception {
        props.setEnabled(true);

        // Scan-like PDF: a fresh upload would tag ocr_pending and run the OCR pipeline.
        when(parsers.parse(eq("application/pdf"), any()))
                .thenReturn(ParseResult.withThumbnailAndScan(null, null, true, 2));
        when(embeddingClient.encodeForCell(anyString(), any())).thenReturn(java.util.List.of(0.1f));

        UUID attId = UUID.randomUUID();
        Map<String, Object> existingRow = new LinkedHashMap<>();
        existingRow.put("id", attId.toString());
        existingRow.put("file_hash", "h");
        existingRow.put("s3_key_original", "orig/f.pdf");
        existingRow.put("s3_key_thumbnail", "thumb.jpg");
        when(repo.findByHash(anyString())).thenReturn(Optional.of(existingRow));
        when(repo.reactivate(eq(attId), any())).thenReturn(existingRow);
        when(repo.findExtractionCellSeed(attId)).thenReturn(Optional.of(
                new AttachmentRepository.ExtractionCellSeed(
                        "Previously OCR'd text", java.util.List.of())));

        Map<String, Object> cellRow = new LinkedHashMap<>();
        cellRow.put("id", UUID.randomUUID().toString());
        when(writeRepo.addCell(anyString(), any(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(cellRow);

        InputStream in = new ByteArrayInputStream(new byte[]{5});
        Map<String, Object> result = service.ingest(
                in, "f.pdf", "application/pdf", "work", "facts", "docs", null, "user");

        // Content is seeded from the prior extraction cell — OCR is NOT re-run.
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(writeRepo).addCell(content.capture(), any(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any());
        assertThat(content.getValue()).isEqualTo("Previously OCR'd text");
        verify(writeRepo, never()).tagOcrPending(any());
        verify(seaweedFs, never()).upload(anyString(), any(java.nio.file.Path.class), anyString());
        assertThat(result.get("deduplicated")).isEqualTo(true);
    }

    @Test
    void imageIngestExtractsExifUpsertsMetaAndPublishesGeocodeWhenGps() throws Exception {
        props.setEnabled(true);

        when(parsers.parse(eq("image/jpeg"), any()))
                .thenReturn(ParseResult.withThumbnail(null, new byte[]{1, 2, 3}));
        when(embeddingClient.encodeForCell(anyString(), any())).thenReturn(java.util.List.of(0.1f));

        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        when(repo.findByHash(anyString())).thenReturn(Optional.empty());
        Map<String, Object> attRow = new LinkedHashMap<>();
        attRow.put("id", attId.toString());
        attRow.put("file_hash", "h");
        attRow.put("s3_key_original", "orig/p.jpg");
        attRow.put("s3_key_thumbnail", "thumb/p.jpg");
        when(repo.insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString(),
                org.mockito.ArgumentMatchers.nullable(Integer.class))).thenReturn(attRow);
        Map<String, Object> cellRow = new LinkedHashMap<>();
        cellRow.put("id", cellId.toString());
        when(writeRepo.addCell(anyString(), any(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(cellRow);

        // REQUIRED: guard does findByAttachmentId(...).isPresent(); default mock returns null → NPE without this.
        when(imageMetaRepo.findByAttachmentId(any())).thenReturn(Optional.empty());

        ExifData exif = new ExifData(120, 80, null, "Apple", "iPhone 16 Pro", 49.4874, 8.4660, 6);
        when(exifExtractor.extract(any())).thenReturn(exif);

        InputStream in = new ByteArrayInputStream(new byte[]{9, 9, 9});
        service.ingest(in, "p.jpg", "image/jpeg", "private", "events", "trip", null, "user");

        verify(imageMetaRepo).upsert(eq(attId), eq(exif), eq("pending"));
        ArgumentCaptor<GeocodeRequestedEvent> ev = ArgumentCaptor.forClass(GeocodeRequestedEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).anySatisfy(e -> {
            assertThat(e.attachmentId()).isEqualTo(attId);
            assertThat(e.lat()).isEqualTo(49.4874);
            assertThat(e.lon()).isEqualTo(8.4660);
        });
    }
}
