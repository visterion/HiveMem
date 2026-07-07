package com.hivemem.summarize;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.search.CellSelectorRepository;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class SummarizerServiceIT {

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
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM summarize_usage");
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");

        mockVistierie = new MockVistierieServer();
        mockVistierie.start();
    }

    @AfterEach
    void tearDown() { mockVistierie.stop(); }

    /**
     * The summarizer must persist EVERY field the LLM produces — summary, key_points,
     * insight, tags and facts — not just the summary. (Regression: summarizeOne used to
     * call reviseCell(content, summary) and silently drop key_points/insight/tags.)
     */
    @Test
    void summarizesLongCell_persistsAllFields() throws Exception {
        UUID id = seedLongCell();

        String inner = "{"
                + "\"summary\":\"HUK Beitragsrechnung Wohngebaeude, Jahresbeitrag 222,60 EUR.\","
                + "\"key_points\":[\"Jahresbeitrag 222,60 EUR ab 21.03.2026\",\"SEPA-Lastschrift\"],"
                + "\"insight\":\"Wird als Beitragsnachweis vom Finanzamt anerkannt.\","
                + "\"tags\":[\"versicherung\",\"huk-coburg\"],"
                + "\"document_type\":\"invoice\","
                + "\"facts\":[{\"predicate\":\"vendor\",\"object\":\"HUK-COBURG\",\"confidence\":0.9}]"
                + "}";
        mockVistierie.stubComplete(inner.replace("\"", "\\\""));

        buildService().summarizeOne(id);

        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT summary, key_points, insight, tags, document_type "
                             + "FROM cells WHERE valid_until IS NULL ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected a revised, current cell row");
            assertEquals("HUK Beitragsrechnung Wohngebaeude, Jahresbeitrag 222,60 EUR.", rs.getString("summary"));

            List<String> keyPoints = toList(rs.getArray("key_points"));
            assertTrue(keyPoints.contains("Jahresbeitrag 222,60 EUR ab 21.03.2026")
                            && keyPoints.contains("SEPA-Lastschrift"),
                    "key_points must be persisted, got: " + keyPoints);

            assertEquals("Wird als Beitragsnachweis vom Finanzamt anerkannt.", rs.getString("insight"));

            List<String> tags = toList(rs.getArray("tags"));
            assertTrue(tags.contains("versicherung") && tags.contains("huk-coburg"),
                    "LLM tags must be persisted, got: " + tags);
            assertFalse(tags.contains("needs_summary"), "needs_summary must be cleared, got: " + tags);

            assertEquals("invoice", rs.getString("document_type"));
        }

        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT predicate, \"object\" FROM facts WHERE predicate='vendor'")) {
            assertTrue(rs.next(), "Expected the extracted vendor fact to be persisted");
            assertEquals("HUK-COBURG", rs.getString("object"));
        }
    }

    /**
     * Loop guard: if the LLM returns no summary, the summarizer must give up (clear the
     * needs_summary tag) rather than reviseCell(content, null) — which would re-add the
     * needs_summary tag on the new revision and reschedule the cell forever.
     */
    @Test
    void nullSummary_givesUpWithoutRetagOrNewRevision() throws Exception {
        UUID id = seedLongCell();

        String inner = "{\"summary\":null,\"key_points\":[],\"insight\":null,"
                + "\"tags\":[],\"document_type\":\"other\",\"facts\":[]}";
        mockVistierie.stubComplete(inner.replace("\"", "\\\""));

        buildService().summarizeOne(id);

        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) AS n FROM cells")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("n"),
                    "No new revision must be spawned for a null summary (reviseCell(content,null) would "
                            + "re-tag needs_summary and reschedule forever)");
        }
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT summary, valid_until IS NULL AS is_current, "
                             + "'needs_summary' = ANY(tags) AS has_tag FROM cells WHERE id='" + id + "'")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("is_current"), "the original cell stays current (not superseded)");
            assertNull(rs.getString("summary"), "summary stays null");
            assertFalse(rs.getBoolean("has_tag"), "needs_summary must be removed (gave up), not re-added");
        }
    }

    /**
     * Tax-relevance tag and valid_from: when the LLM marks a document as tax-relevant and
     * provides a document_date fact, summarizeOne must apply the "steuerrelevant" tag (for
     * German instance default) and set valid_from to the document date.
     */
    @Test
    void taxRelevantDoc_appliesTagAndSetsValidFrom() throws Exception {
        UUID id = seedLongCell();

        String inner = "{"
                + "\"document_type\":\"invoice\","
                + "\"summary\":\"Malerrechnung.\","
                + "\"key_points\":[],"
                + "\"insight\":null,"
                + "\"tags\":[],"
                + "\"language\":\"de\","
                + "\"tax_relevant\":true,"
                + "\"facts\":[{\"predicate\":\"document_date\",\"object\":\"2025-03-09\",\"confidence\":0.9}]"
                + "}";
        mockVistierie.stubComplete(inner.replace("\"", "\\\""));

        buildService().summarizeOne(id);

        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tags, valid_from FROM cells WHERE valid_until IS NULL ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected a revised, current cell row");

            List<String> tags = toList(rs.getArray("tags"));
            assertThat(tags).contains("steuerrelevant");
            assertThat(tags).contains("tax_scanned");

            OffsetDateTime actual = rs.getObject("valid_from", OffsetDateTime.class);
            assertThat(actual.toInstant())
                    .isEqualTo(OffsetDateTime.parse("2025-03-09T00:00:00Z").toInstant());
        }
    }

    // --- helpers ---

    private UUID seedLongCell() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            String content = "a".repeat(2000);
            st.execute(
                    "INSERT INTO cells (id, content, realm, signal, status, tags, embedding, created_at, valid_from) "
                            + "VALUES ('" + id + "', '" + content + "', 'documents', 'facts', 'committed', "
                            + "ARRAY['needs_summary']::text[], NULL, now(), now())");
        }
        return id;
    }

    private SummarizerService buildService() {
        SummarizerProperties props = new SummarizerProperties();
        props.setEnabled(true);
        props.setVistierieBaseUrl(mockVistierie.baseUrl());
        props.setVistierieToken("test-token");
        props.setModel("claude-haiku-4-5");
        props.setMaxInputChars(8000);
        props.setDailyBudgetUsd(10.0);
        props.setBackfillBatchSize(10);

        ExtractionProperties extractionProps = new ExtractionProperties();
        extractionProps.setEnabled(false); // deterministic fallback profile

        SummarizerRepository repo = new SummarizerRepository(dsl);
        WriteToolRepository writeRepo = new WriteToolRepository(dsl);
        EmbeddingClient embedding = new FixedEmbeddingClient();

        InstanceConfig instanceConfig = new InstanceConfig(dsl);
        try {
            var initMethod = InstanceConfig.class.getDeclaredMethod("initialize");
            initMethod.setAccessible(true);
            initMethod.invoke(instanceConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        OpLogWriter opLogWriter = new OpLogWriter(dsl, instanceConfig, new ObjectMapper());

        SyncPeerRepository peerRepo = mock(SyncPeerRepository.class);
        SyncOpsRepository syncOpsRepo = mock(SyncOpsRepository.class);
        PeerClient peerClient = mock(PeerClient.class);
        PushDispatcher pushDispatcher = new PushDispatcher(peerRepo, syncOpsRepo, peerClient, instanceConfig);

        org.springframework.context.ApplicationEventPublisher noopPublisher = e -> {};
        WriteToolService writeService = new WriteToolService(
                writeRepo, embedding, opLogWriter, pushDispatcher, noopPublisher, new CellSelectorRepository(dsl));

        return new SummarizerService(
                props, extractionProps, repo, dsl, RestClient.builder(), writeService,
                new ExtractionProfileRegistry(), null);
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }

    private static List<String> toList(Array sqlArray) throws Exception {
        if (sqlArray == null) return List.of();
        Object[] inner = (Object[]) sqlArray.getArray();
        return Arrays.stream(inner).map(String::valueOf).toList();
    }
}
