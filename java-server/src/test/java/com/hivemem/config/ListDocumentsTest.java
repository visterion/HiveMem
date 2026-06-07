package com.hivemem.config;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.DocumentListRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ListDocumentsTest.TestConfig.class)
class ListDocumentsTest {

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
    DocumentListRepository documentListRepository;

    @Autowired
    DSLContext dslContext;

    // Fixed UUIDs for deterministic ordering
    static final UUID ID_CONTRACT_1  = UUID.fromString("00000000-0000-0000-0002-000000000001");
    static final UUID ID_CONTRACT_2  = UUID.fromString("00000000-0000-0000-0002-000000000002");
    static final UUID ID_INVOICE     = UUID.fromString("00000000-0000-0000-0002-000000000003");
    static final UUID ID_PENDING     = UUID.fromString("00000000-0000-0000-0002-000000000004");
    static final UUID ID_ATTACHMENT  = UUID.fromString("00000000-0000-0000-0002-000000000010");
    static final UUID ID_CELL_ATT    = UUID.fromString("00000000-0000-0000-0002-000000000011");
    // Confidence test cell
    static final UUID ID_CONF_DOC    = UUID.fromString("00000000-0000-0000-0002-000000000020");
    static final UUID ID_FACT_HIGH   = UUID.fromString("00000000-0000-0000-0002-000000000021");
    static final UUID ID_FACT_LOW    = UUID.fromString("00000000-0000-0000-0002-000000000022");

    @BeforeEach
    void seed() {
        dslContext.execute("DELETE FROM facts WHERE id IN (?, ?)", ID_FACT_HIGH, ID_FACT_LOW);
        dslContext.execute("DELETE FROM cell_attachments WHERE cell_id IN (?, ?, ?, ?)",
                ID_CONTRACT_1, ID_CONTRACT_2, ID_INVOICE, ID_PENDING);
        dslContext.execute("DELETE FROM attachments WHERE id = ?", ID_ATTACHMENT);
        dslContext.execute("DELETE FROM cells WHERE realm = 'ldocs'");

        // 3 committed docs with varied tags and different created_at
        // contract_1: oldest (2024-01-01), tags=[contract]
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'contract alpha', 'ldocs', 'facts', 'contracts', ?, 'committed', now(), '2024-01-01'::date)",
                ID_CONTRACT_1, new String[]{"contract"});

        // contract_2 + paid: middle (2025-01-01), tags=[contract, paid]
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'contract beta paid', 'ldocs', 'facts', 'contracts', ?, 'committed', now(), '2025-01-01'::date)",
                ID_CONTRACT_2, new String[]{"contract", "paid"});

        // invoice: newest (2026-01-01), tags=[invoice]
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'invoice gamma', 'ldocs', 'facts', 'invoices', ?, 'committed', now(), '2026-01-01'::date)",
                ID_INVOICE, new String[]{"invoice"});

        // 1 pending doc
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'pending contract', 'ldocs', 'facts', 'contracts', ?, 'pending', now(), '2025-06-01'::date)",
                ID_PENDING, new String[]{"contract"});

        // Attachment for ID_INVOICE (page_count=5, non-null s3_key_thumbnail)
        dslContext.execute(
                "INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, page_count, uploaded_by, created_at) " +
                "VALUES (?, ?, 'application/pdf', 'invoice-gamma.pdf', 102400, " +
                "'originals/invoice.pdf', 'thumbnails/invoice.jpg', 5, 'system', now())",
                ID_ATTACHMENT, "hash-" + ID_ATTACHMENT);
        dslContext.execute(
                "INSERT INTO cell_attachments (id, cell_id, attachment_id, extraction_source, created_at) " +
                "VALUES (?, ?, ?, true, now())",
                ID_CELL_ATT, ID_INVOICE, ID_ATTACHMENT);
    }

    @Test
    void defaultBrowseReturnsCommittedDocsSortedNewestFirst() {
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, null, null, "newest", 50, 0);

        // Only 3 committed, not the pending one
        assertThat(rows).hasSize(3);

        // Newest first: invoice(2026), contract_2(2025), contract_1(2024)
        assertThat(rows.get(0).get("id")).isEqualTo(ID_INVOICE.toString());
        assertThat(rows.get(1).get("id")).isEqualTo(ID_CONTRACT_2.toString());
        assertThat(rows.get(2).get("id")).isEqualTo(ID_CONTRACT_1.toString());
    }

    @Test
    void tagFilterReturnsOnlyContractTaggedDocs() {
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, List.of("contract"), "committed", "newest", 50, 0);

        // contract_1 and contract_2 are tagged 'contract'; invoice is not
        assertThat(rows).hasSize(2);
        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        assertThat(ids).contains(ID_CONTRACT_1.toString(), ID_CONTRACT_2.toString());
        assertThat(ids).doesNotContain(ID_INVOICE.toString());
    }

    @Test
    void statusPendingReturnsOnlyPendingDoc() {
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, null, "pending", "newest", 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(ID_PENDING.toString());
    }

    @Test
    void docWithAttachmentExposesAttachmentFieldsCorrectly() {
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, null, "committed", "newest", 50, 0);

        // invoice is the first (newest)
        Map<String, Object> invoiceRow = rows.get(0);
        assertThat(invoiceRow.get("id")).isEqualTo(ID_INVOICE.toString());
        assertThat(invoiceRow.get("attachment_id")).isNotNull();
        assertThat(invoiceRow.get("attachment_id")).isEqualTo(ID_ATTACHMENT.toString());
        assertThat(invoiceRow.get("page_count")).isEqualTo(5);
        assertThat(invoiceRow.get("has_thumbnail")).isEqualTo(true);

        // contract_2 has no attachment
        Map<String, Object> contractRow = rows.get(1);
        assertThat(contractRow.get("attachment_id")).isNull();
        assertThat(contractRow.get("has_thumbnail")).isEqualTo(false);
    }

    @Test
    void pagingWithLimitAndOffsetReturnsSecondRow() {
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, null, "committed", "newest", 1, 1);

        // offset=1 skips the newest (invoice), returns contract_2
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(ID_CONTRACT_2.toString());
    }

    @Test
    void docWithTwoActiveFactsHasConfidenceAverage() {
        // Insert a document cell in ldocs
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'confidence test doc', 'ldocs', 'facts', 'confidence', ?, 'committed', now(), '2026-03-01'::date)",
                ID_CONF_DOC, new String[]{"conf-test"});

        // Insert 2 active facts with source_id = ID_CONF_DOC and confidences 0.8 and 0.6
        dslContext.execute(
                "INSERT INTO facts (id, subject, predicate, object, confidence, source_id, status, created_by, " +
                "created_at, valid_from) VALUES (?, 'doc', 'has', 'vendor_a', 0.8, ?, 'committed', 'system', now(), now())",
                ID_FACT_HIGH, ID_CONF_DOC);
        dslContext.execute(
                "INSERT INTO facts (id, subject, predicate, object, confidence, source_id, status, created_by, " +
                "created_at, valid_from) VALUES (?, 'doc', 'has', 'vendor_b', 0.6, ?, 'committed', 'system', now(), now())",
                ID_FACT_LOW, ID_CONF_DOC);

        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, List.of("conf-test"), "committed", "newest", 50, 0);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("id")).isEqualTo(ID_CONF_DOC.toString());
        Double confidence = (Double) row.get("confidence");
        assertThat(confidence).isNotNull();
        assertThat(confidence).isCloseTo(0.7, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void docWithNoActiveFactsHasNullConfidence() {
        // contract_1 has no facts seeded — confidence must be null
        List<Map<String, Object>> rows = documentListRepository.listDocuments(
                "ldocs", null, null, List.of("contract"), "committed", "newest", 50, 0);

        // Both contract_1 and contract_2 have no facts; both should have null confidence
        assertThat(rows).hasSizeGreaterThanOrEqualTo(1);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("confidence"))
                    .as("confidence for doc %s with no active facts should be null", row.get("id"))
                    .isNull();
        }
    }
}
