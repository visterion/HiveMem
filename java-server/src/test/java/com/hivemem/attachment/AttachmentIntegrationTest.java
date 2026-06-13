package com.hivemem.attachment;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AttachmentIntegrationTest.TestConfig.class)
class AttachmentIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data")
            .withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(code -> code == 400 || (code >= 200 && code < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("hivemem.attachment.enabled", () -> "true");
        registry.add("hivemem.attachment.s3-endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
        registry.add("hivemem.attachment.s3-access-key", () -> "");
        registry.add("hivemem.attachment.s3-secret-key", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired org.jooq.DSLContext dsl;

    private static final tools.jackson.databind.ObjectMapper OM = new tools.jackson.databind.ObjectMapper();

    private static final AuthPrincipal WRITER = new AuthPrincipal("test-writer", AuthRole.WRITER);
    private static final AuthPrincipal READER = new AuthPrincipal("test-reader", AuthRole.READER);
    private static final AuthPrincipal ADMIN  = new AuthPrincipal("test-admin",  AuthRole.ADMIN);

    private UUID existingCellId;

    @BeforeEach
    void setUp() {
        dsl.execute("TRUNCATE TABLE cell_attachments, attachments, tunnels, cells CASCADE");
        existingCellId = (UUID) dsl.fetchOne("""
                INSERT INTO cells (content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES ('existing cell', array_fill(0::real, ARRAY[1024])::vector,
                        'TestRealm', 'facts', 'TestTopic', 'committed', 'test', now())
                RETURNING id""").get("id");
    }

    @Test
    void uploadPdfCreatesExtractionCellAndDownloadWorks() throws Exception {
        byte[] pdf = buildPdf("Integration test PDF content");

        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "test.pdf", "application/pdf", pdf))
                        .param("realm",  "TestRealm")
                        .param("signal", "facts")
                        .param("topic",  "TestTopic")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mime_type").value("application/pdf"))
                .andExpect(jsonPath("$.cell_id").isNotEmpty())
                .andExpect(jsonPath("$.has_thumbnail").value(true))
                .andReturn().getResponse().getContentAsString();

        String attachmentId = OM.readTree(body).get("id").asText();
        String cellId       = OM.readTree(body).get("cell_id").asText();

        // Verify extraction Cell exists and is pending
        String cellStatus = (String) dsl.fetchOne(
                "SELECT status FROM cells WHERE id = ?::uuid", cellId).get("status");
        assertEquals("pending", cellStatus);

        // Verify cell_attachments link
        long linkCount = dsl.fetchOne(
                "SELECT COUNT(*) FROM cell_attachments WHERE attachment_id = ?::uuid AND cell_id = ?::uuid AND extraction_source = true",
                attachmentId, cellId).get(0, Long.class);
        assertEquals(1L, linkCount);

        // Verify download works
        mockMvc.perform(get("/api/attachments/" + attachmentId + "/content")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")));
    }

    @Test
    void deduplicatesAttachmentButCreatesNewCell() throws Exception {
        byte[] pdf = buildPdf("Dedup test");

        String body1 = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "dedup.pdf", "application/pdf", pdf))
                        .param("realm", "TestRealm")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "dedup.pdf", "application/pdf", pdf))
                        .param("realm", "TestRealm")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String attachmentId1 = OM.readTree(body1).get("id").asText();
        String attachmentId2 = OM.readTree(body2).get("id").asText();
        String cellId1       = OM.readTree(body1).get("cell_id").asText();
        String cellId2       = OM.readTree(body2).get("cell_id").asText();

        // Same attachment row
        assertEquals(attachmentId1, attachmentId2);
        // Different extraction cells
        assertNotEquals(cellId1, cellId2);
    }

    @Test
    void uploadWithCellIdCreatesTunnel() throws Exception {
        byte[] pdf = buildPdf("Tunnel test");

        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "tunnel.pdf", "application/pdf", pdf))
                        .param("realm",   "TestRealm")
                        .param("cell_id", existingCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String newCellId = OM.readTree(body).get("cell_id").asText();

        long tunnelCount = dsl.fetchOne("""
                SELECT COUNT(*) FROM tunnels
                WHERE from_cell = ?::uuid AND to_cell = ?::uuid AND relation = 'related_to'
                  AND valid_until IS NULL
                """, newCellId, existingCellId.toString()).get(0, Long.class);
        assertEquals(1L, tunnelCount);
    }

    @Test
    void uploadWithoutRealmReturns400() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}))
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readerCannotUpload() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}))
                        .param("realm", "TestRealm")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSoftDelete() throws Exception {
        byte[] pdf = buildPdf("To be soft deleted");
        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "soft.pdf", "application/pdf", pdf))
                        .param("realm", "TestRealm")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = OM.readTree(body).get("id").asText();

        mockMvc.perform(delete("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminCannotDelete() throws Exception {
        byte[] pdf = buildPdf("Protected");
        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "del.pdf", "application/pdf", pdf))
                        .param("realm", "TestRealm")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = OM.readTree(body).get("id").asText();

        mockMvc.perform(delete("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isForbidden());
    }

    private byte[] buildPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
