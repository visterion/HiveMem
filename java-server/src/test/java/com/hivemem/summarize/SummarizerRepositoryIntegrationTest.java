package com.hivemem.summarize;

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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SummarizerRepositoryIntegrationTest.TestConfig.class)
class SummarizerRepositoryIntegrationTest {

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
    @Autowired SummarizerRepository repo;

    @Test
    void tagAndRemoveNeedsSummaryIsIdempotent() {
        UUID id = insertCell("content", null);
        repo.tagNeedsSummary(id);
        repo.tagNeedsSummary(id); // no duplicate

        assertThat(repo.findCellsNeedingSummary(10)).contains(id);

        repo.removeNeedsSummaryTag(id);
        assertThat(repo.findCellsNeedingSummary(10)).doesNotContain(id);
        // removing again is a no-op
        repo.removeNeedsSummaryTag(id);
    }

    @Test
    void findCellsNeedingSummarySkipsRecentlyThrottled() {
        UUID a = insertCell("content a", null);
        repo.tagNeedsSummary(a);
        repo.tagThrottled(a);

        // recently throttled cell should be excluded
        assertThat(repo.findCellsNeedingSummary(10)).doesNotContain(a);
    }

    @Test
    void findCellSnapshotReturnsContentAndTags() {
        UUID id = insertCell("hello", "world");
        repo.tagNeedsSummary(id);

        Optional<SummarizerRepository.CellSnapshot> snap = repo.findCellSnapshot(id);
        assertThat(snap).isPresent();
        assertThat(snap.get().content()).isEqualTo("hello");
        assertThat(snap.get().summary()).isEqualTo("world");
        assertThat(snap.get().tags()).contains("needs_summary");
    }

    @Test
    void findCellSnapshotEmptyForUnknownId() {
        assertThat(repo.findCellSnapshot(UUID.randomUUID())).isEmpty();
    }

    @Test
    void setDocumentTypeUpdatesColumn() {
        UUID id = insertCell("c", null);
        repo.setDocumentType(id, "code");

        String docType = dsl.fetchOne("SELECT document_type FROM cells WHERE id = ?", id)
                .get("document_type", String.class);
        assertThat(docType).isEqualTo("code");
    }

    @Test
    void findCellAttachmentMetaReturnsMimeAndFilename() {
        UUID cellId = insertCell("att-content", null);
        UUID attId = insertAttachment("text/markdown", "doc.md");
        dsl.execute("""
                INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source)
                VALUES (?, ?, true)
                """, cellId, attId);

        Optional<SummarizerRepository.AttachmentMeta> meta = repo.findCellAttachmentMeta(cellId);
        assertThat(meta).isPresent();
        assertThat(meta.get().mimeType()).isEqualTo("text/markdown");
        assertThat(meta.get().filename()).isEqualTo("doc.md");
    }

    @Test
    void findCellAttachmentMetaEmptyWhenNoAttachment() {
        UUID cellId = insertCell("c", null);
        assertThat(repo.findCellAttachmentMeta(cellId)).isEmpty();
    }

    @Test
    void setValidFromUpdatesCell() {
        UUID id = insertCell("content for validfrom", null);
        repo.setValidFrom(id, java.time.OffsetDateTime.parse("2025-03-09T00:00:00Z"));

        var row = dsl.fetchOne("SELECT valid_from FROM cells WHERE id = ?", id);
        assertThat(row.get("valid_from", java.time.OffsetDateTime.class).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2025, 3, 9));
    }

    @Test
    void applyTagIsIdempotent() {
        UUID id = insertCell("content for tag", null);
        repo.applyTag(id, "steuerrelevant");
        repo.applyTag(id, "steuerrelevant");

        var tags = dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", id)
                .get("tags", String[].class);
        assertThat(tags).containsOnlyOnce("steuerrelevant");
    }

    @Test
    void findDocumentsNeedingTaxScanExcludesTaggedCells() {
        UUID tagged = insertCell("tagged doc", "summary");
        dsl.execute("UPDATE cells SET realm='documents', tags=ARRAY['tax_scanned'] WHERE id=?", tagged);

        UUID untagged = insertCell("untagged doc", "summary");
        dsl.execute("UPDATE cells SET realm='documents' WHERE id=?", untagged);

        var ids = repo.findDocumentsNeedingTaxScan(10);
        assertThat(ids).contains(untagged);
        assertThat(ids).doesNotContain(tagged);
    }

    private UUID insertCell(String content, String summary) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, summary, valid_from)
                VALUES (?::uuid, ?, array_fill(0, ARRAY[1024])::vector, 'eng', 'facts', 'test', 'committed', ?, now())
                """, id, content, summary);
        return id;
    }

    private UUID insertAttachment(String mime, String filename) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes,
                                         s3_key_original, uploaded_by)
                VALUES (?::uuid, ?, ?, ?, 1, 'k', 'u')
                """, id, "h-" + id, mime, filename);
        return id;
    }
}
