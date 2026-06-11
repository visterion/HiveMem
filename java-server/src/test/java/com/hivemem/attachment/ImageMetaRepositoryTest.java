package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ImageMetaRepositoryTest.TestConfig.class)
class ImageMetaRepositoryTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired ImageMetaRepository repo;
    @Autowired DSLContext dsl;

    static final UUID ATT = UUID.fromString("00000000-0000-0000-0033-000000000001");

    @BeforeEach
    void seed() {
        dsl.execute("DELETE FROM attachment_image_meta WHERE attachment_id = ?", ATT);
        dsl.execute("DELETE FROM attachments WHERE id = ?", ATT);
        dsl.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, uploaded_by, created_at) VALUES (?, ?, 'image/jpeg', 'p.jpg', 100, " +
                "'orig/p.jpg', 'system', now())", ATT, "hash-" + ATT);
    }

    @Test
    void upsertInsertsThenUpdatesIdempotently() {
        ExifData d = new ExifData(120, 80,
                OffsetDateTime.of(2026, 5, 12, 14, 30, 0, 0, ZoneOffset.UTC),
                "Apple", "iPhone 16 Pro", 49.4874, 8.4660, 6);
        repo.upsert(ATT, d, "pending");

        Optional<ImageMetaRepository.ImageMetaRow> row = repo.findByAttachmentId(ATT);
        assertThat(row).isPresent();
        assertThat(row.get().width()).isEqualTo(120);
        assertThat(row.get().cameraModel()).isEqualTo("iPhone 16 Pro");
        assertThat(row.get().geocodeStatus()).isEqualTo("pending");

        // Second upsert with different data overwrites (idempotent for backfill/re-ingest).
        repo.upsert(ATT, new ExifData(200, 100, null, null, null, null, null, null), "none");
        assertThat(repo.findByAttachmentId(ATT).get().width()).isEqualTo(200);
        assertThat(repo.findByAttachmentId(ATT).get().geocodeStatus()).isEqualTo("none");
    }

    @Test
    void updatePlaceSetsNameAndStatus() {
        repo.upsert(ATT, new ExifData(1, 1, null, null, null, 49.0, 8.0, null), "pending");
        repo.updatePlace(ATT, "Mannheim, DE", "done");
        ImageMetaRepository.ImageMetaRow row = repo.findByAttachmentId(ATT).orElseThrow();
        assertThat(row.placeName()).isEqualTo("Mannheim, DE");
        assertThat(row.geocodeStatus()).isEqualTo("done");
    }

    @Test
    void findImageAttachmentsWithoutMetaReturnsTheUnbackfilledImage() {
        List<UUID> missing = repo.findImageAttachmentsWithoutMeta();
        assertThat(missing).contains(ATT);
        repo.upsert(ATT, ExifData.EMPTY, "none");
        assertThat(repo.findImageAttachmentsWithoutMeta()).doesNotContain(ATT);
    }
}
