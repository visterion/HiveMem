package com.hivemem.extraction;

import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.summarize.AnthropicSummarizer;
import com.hivemem.summarize.SummarizerProperties;
import com.hivemem.summarize.SummarizerRepository;
import com.hivemem.summarize.SummarizerService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import com.hivemem.testsupport.MockVistierieServer;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class ExtractionIntegrationIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private MockVistierieServer mockVistierie;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM summarize_usage");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");

        mockVistierie = new MockVistierieServer();
        mockVistierie.start();
    }

    @AfterEach
    void tearDown() { mockVistierie.stop(); }

    @Test
    void invoiceCellGetsDocumentTypeAndFactsPersisted() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            String content = "Rechnungsnummer 12345 Stadtwerke München Betrag 234.56 EUR " + "x".repeat(600);
            st.execute(
                    "INSERT INTO cells (id, content, realm, signal, status, tags, embedding, created_at, valid_from) "
                            + "VALUES ('" + id + "', '" + content + "', 'test', 'facts', 'committed', "
                            + "ARRAY['needs_summary']::text[], NULL, now(), now())");
        }

        // Vistierie stub — "text" field is the LLM output JSON that AnthropicSummarizer parses
        mockVistierie.stubComplete(
                "{\\\"summary\\\":\\\"Stadtwerke M\\\\u00fcnchen \\\\u2013 234.56 EUR\\\",\\\"key_points\\\":[]," +
                "\\\"insight\\\":null,\\\"tags\\\":[\\\"invoice\\\"],\\\"document_type\\\":\\\"invoice\\\"," +
                "\\\"facts\\\":[{\\\"predicate\\\":\\\"vendor\\\",\\\"object\\\":\\\"Stadtwerke M\\\\u00fcnchen\\\"," +
                "\\\"confidence\\\":0.98},{\\\"predicate\\\":\\\"amount_total\\\",\\\"object\\\":\\\"234.56\\\"," +
                "\\\"confidence\\\":0.99},{\\\"predicate\\\":\\\"currency\\\",\\\"object\\\":\\\"EUR\\\"," +
                "\\\"confidence\\\":1.0}]}");

        SummarizerRepository repo = new SummarizerRepository(dsl);
        WriteToolRepository writeRepo = new WriteToolRepository(dsl);
        InstanceConfig instanceConfig = new InstanceConfig(dsl);
        var initMethod = InstanceConfig.class.getDeclaredMethod("initialize");
        initMethod.setAccessible(true);
        initMethod.invoke(instanceConfig);
        OpLogWriter opLogWriter = new OpLogWriter(dsl, instanceConfig, new ObjectMapper());
        SyncPeerRepository peerRepo = mock(SyncPeerRepository.class);
        SyncOpsRepository syncOpsRepo = mock(SyncOpsRepository.class);
        PeerClient peerClient = mock(PeerClient.class);
        PushDispatcher pushDispatcher = new PushDispatcher(peerRepo, syncOpsRepo, peerClient, instanceConfig);
        ApplicationEventPublisher noopPublisher = e -> {};
        WriteToolService writeService = new WriteToolService(
                writeRepo, new FixedEmbeddingClient(), opLogWriter, pushDispatcher, noopPublisher);

        SummarizerProperties props = new SummarizerProperties();
        props.setEnabled(true);
        props.setVistierieBaseUrl(mockVistierie.baseUrl());
        props.setVistierieToken("test-token");
        props.setModel("claude-haiku-4-5");
        props.setDailyBudgetUsd(1.0);

        ExtractionProperties extractionProps = new ExtractionProperties();
        ExtractionProfileRegistry registry = new ExtractionProfileRegistry();

        SummarizerService service = new SummarizerService(
                props, extractionProps, repo, dsl, RestClient.builder(), writeService, registry, null);

        var summarizeOne = SummarizerService.class.getDeclaredMethod("summarizeOne", UUID.class);
        summarizeOne.setAccessible(true);
        summarizeOne.invoke(service, id);

        // Assert: the new (revised) cell has document_type=invoice
        var newRow = dsl.fetchOne(
                "SELECT document_type FROM cells WHERE valid_until IS NULL "
                        + "AND content LIKE 'Rechnungsnummer 12345 Stadtwerke%' "
                        + "ORDER BY created_at DESC LIMIT 1");
        assertNotNull(newRow, "Expected a revised cell row");
        assertEquals("invoice", newRow.get("document_type", String.class));

        // Assert: facts table has 3 rows for this cell (source_id points at the revised cell)
        var factCount = dsl.fetchOne(
                "SELECT count(*) AS n FROM facts WHERE source_id IN "
                        + "(SELECT id FROM cells WHERE content LIKE 'Rechnungsnummer 12345%')");
        assertNotNull(factCount);
        assertEquals(3L, ((Number) factCount.get("n")).longValue());

        // Assert: vendor predicate object equals "Stadtwerke München"
        var vendorRow = dsl.fetchOne(
                "SELECT \"object\" FROM facts WHERE predicate = 'vendor' "
                        + "AND source_id IN (SELECT id FROM cells WHERE content LIKE 'Rechnungsnummer 12345%')");
        assertNotNull(vendorRow, "Expected a vendor fact");
        assertEquals("Stadtwerke München", vendorRow.get("object", String.class));
    }
}
