package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AttachmentPageCountTest.TestConfig.class)
class AttachmentPageCountTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
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

    @Autowired
    DSLContext dsl;

    @Autowired
    AttachmentRepository repo;

    // ── (a) Repository round-trip ────────────────────────────────────────────

    @Test
    void insertWithPageCountAndFindByCellId() {
        UUID cellId = insertMinimalCell();
        Map<String, Object> row = repo.insert(
                "hash-pc-1", "application/pdf", "document.pdf",
                1234L, "key-orig-pc", null, "tester", 3);
        UUID attachmentId = UUID.fromString((String) row.get("id"));

        // page_count should come back from insert
        assertThat(row.get("page_count")).isEqualTo(3);

        // link and retrieve via findByCellId
        dsl.execute("""
                INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source)
                VALUES (?::uuid, ?::uuid, true)
                ON CONFLICT DO NOTHING
                """, cellId, attachmentId);

        List<Map<String, Object>> found = repo.findByCellId(cellId);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().get("page_count")).isEqualTo(3);
    }

    @Test
    void insertWithNullPageCountReturnsNull() {
        Map<String, Object> row = repo.insert(
                "hash-pc-null", "text/plain", "file.txt",
                100L, "key-pc-null", null, "tester", null);
        assertThat(row.get("page_count")).isNull();
    }

    // ── (b) PDF page count helper ────────────────────────────────────────────

    @Test
    void pdfPageCountHelperReturns3ForThreePagePdf() throws Exception {
        // Build a 3-page PDF in-memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(baos);
        }

        Path tempFile = Files.createTempFile("test-pdf-", ".pdf");
        try {
            Files.write(tempFile, baos.toByteArray());
            Integer count = AttachmentService.pdfPageCount(tempFile, "application/pdf");
            assertThat(count).isEqualTo(3);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void pdfPageCountHelperReturnsNullForNonPdf() throws Exception {
        Path tempFile = Files.createTempFile("test-notpdf-", ".txt");
        try {
            Files.writeString(tempFile, "hello world");
            Integer count = AttachmentService.pdfPageCount(tempFile, "text/plain");
            assertThat(count).isNull();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void pdfPageCountHelperReturnsNullForGarbagePdf() throws Exception {
        Path tempFile = Files.createTempFile("test-garbage-", ".pdf");
        try {
            Files.write(tempFile, new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02}); // corrupt PDF
            Integer count = AttachmentService.pdfPageCount(tempFile, "application/pdf");
            assertThat(count).isNull();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertMinimalCell() {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, valid_from)
                VALUES (?::uuid, 'x', array_fill(0, ARRAY[1024])::vector, 'eng', 'facts', 'test', 'committed', now())
                """, id);
        return id;
    }
}
