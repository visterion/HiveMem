package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AttachmentRepositoryIntegrationTest.TestConfig.class)
class AttachmentRepositoryIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
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
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired DSLContext dsl;
    @Autowired AttachmentRepository repo;

    @Test
    void insertFindByHashAndById() {
        Map<String, Object> row = repo.insert("hash-1", "text/plain", "file.txt",
                42L, "key-orig", "key-thumb", "user-a", null);
        UUID id = UUID.fromString((String) row.get("id"));

        assertThat(repo.findByHash("hash-1")).isPresent();
        assertThat(repo.findById(id)).isPresent();
        assertThat(repo.findByHash("nonexistent")).isEmpty();
    }

    @Test
    void reactivateClearsDeletedAtAndFillsThumbnailWhenNull() {
        Map<String, Object> row = repo.insert("hash-2", "image/png", "x.png",
                10L, "k", null, "u", null);
        UUID id = UUID.fromString((String) row.get("id"));

        // soft-delete first
        assertThat(repo.softDelete(id)).isTrue();
        assertThat(repo.findById(id)).isEmpty(); // findById filters deleted_at

        Map<String, Object> reactivated = repo.reactivate(id, "thumb-key");
        assertThat(reactivated.get("s3_key_thumbnail")).isEqualTo("thumb-key");
        assertThat(repo.findById(id)).isPresent();
    }

    @Test
    void reactivateThrowsWhenIdMissing() {
        assertThatThrownBy(repo, UUID.randomUUID());
    }

    private static void assertThatThrownBy(AttachmentRepository repo, UUID id) {
        try {
            repo.reactivate(id, null);
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.util.NoSuchElementException.class);
        }
    }

    @Test
    void softDeleteReturnsFalseWhenAlreadyDeleted() {
        Map<String, Object> row = repo.insert("hash-3", "text/plain", "f.txt", 1L, "k3", null, "u", null);
        UUID id = UUID.fromString((String) row.get("id"));
        assertThat(repo.softDelete(id)).isTrue();
        assertThat(repo.softDelete(id)).isFalse();
    }

    @Test
    void linkExtractionCellAndFindByCellId() {
        UUID cellId = insertMinimalCell();
        Map<String, Object> a = repo.insert("hash-4", "text/plain", "f.txt", 1L, "k4", null, "u", null);
        UUID attachmentId = UUID.fromString((String) a.get("id"));

        repo.linkExtractionCell(attachmentId, cellId);
        // second call is a no-op (ON CONFLICT DO NOTHING)
        repo.linkExtractionCell(attachmentId, cellId);

        List<Map<String, Object>> rows = repo.findByCellId(cellId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("file_hash")).isEqualTo("hash-4");
    }

    @Test
    void findAttachmentForCellReturnsExtractionSource() {
        UUID cellId = insertMinimalCell();
        Map<String, Object> a = repo.insert("hash-5", "image/png", "x.png", 1L, "k5", null, "u", null);
        UUID attachmentId = UUID.fromString((String) a.get("id"));
        repo.linkExtractionCell(attachmentId, cellId);

        Optional<AttachmentRepository.AttachmentInfo> info = repo.findAttachmentForCell(cellId);
        assertThat(info).isPresent();
        assertThat(info.get().attachmentId()).isEqualTo(attachmentId);
        assertThat(info.get().mimeType()).isEqualTo("image/png");
        assertThat(info.get().s3KeyOriginal()).isEqualTo("k5");

        assertThat(repo.findAttachmentForCell(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updateThumbnailKeyAndFindDiagramsWithoutThumbnail() {
        UUID cellId = insertMinimalCell();
        Map<String, Object> a = repo.insert("hash-6", "text/vnd.mermaid", "g.mmd",
                1L, "k6", null, "u", null);
        UUID attachmentId = UUID.fromString((String) a.get("id"));
        repo.linkExtractionCell(attachmentId, cellId);

        List<AttachmentRepository.DiagramRow> diagrams =
                repo.findDiagramsWithoutThumbnail(Set.of("text/vnd.mermaid"), 10);
        assertThat(diagrams).extracting(AttachmentRepository.DiagramRow::attachmentId)
                .contains(attachmentId);

        repo.updateThumbnailKey(attachmentId, "thumb-now");
        List<AttachmentRepository.DiagramRow> after =
                repo.findDiagramsWithoutThumbnail(Set.of("text/vnd.mermaid"), 10);
        assertThat(after).extracting(AttachmentRepository.DiagramRow::attachmentId)
                .doesNotContain(attachmentId);
    }

    @Test
    void findDiagramsWithoutThumbnailEmptyMimeReturnsEmpty() {
        assertThat(repo.findDiagramsWithoutThumbnail(Set.of(), 10)).isEmpty();
        assertThat(repo.findDiagramsWithoutThumbnail(null, 10)).isEmpty();
    }

    @Test
    void findCellsWithVisionPendingReturnsTaggedCells() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, tags, valid_from)
                VALUES (?::uuid, 'img', array_fill(0, ARRAY[1024])::vector, 'eng', 'facts', 'test', 'committed',
                        ARRAY['vision_pending'], now())
                """, cellId);

        List<UUID> ids = repo.findCellsWithVisionPending(10);
        assertThat(ids).contains(cellId);
    }

    private UUID insertMinimalCell() {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, valid_from)
                VALUES (?::uuid, 'x', array_fill(0, ARRAY[1024])::vector, 'eng', 'facts', 'test', 'committed', now())
                """, id);
        return id;
    }
}
