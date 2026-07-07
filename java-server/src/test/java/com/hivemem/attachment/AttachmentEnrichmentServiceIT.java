package com.hivemem.attachment;

import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import com.hivemem.testsupport.MockVistierieServer;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Testcontainers
class AttachmentEnrichmentServiceIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private SeaweedFsClient seaweed;

    @BeforeEach
    void setUp() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM vision_usage");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");

        seaweed = mock(SeaweedFsClient.class);
        when(seaweed.download(anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}));
    }

    @Test
    void krokiThumbnailFlow_persistsThumbnailKey() throws Exception {
        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        String fileHash = "abc123";
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, "
                    + "s3_key_original, uploaded_by) VALUES ('" + attId + "', '" + fileHash + "', "
                    + "'text/x-mermaid', 'graph.mmd', 30, '" + fileHash + ".mmd', 'tester')");
            st.execute("INSERT INTO cells (id, content, realm, signal, status, tags, embedding, created_at, valid_from) "
                    + "VALUES ('" + cellId + "', 'graph TD; A-->B', 'test', 'facts', 'committed', "
                    + "ARRAY['kroki_pending']::text[], NULL, now(), now())");
            st.execute("INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source, created_at) "
                    + "VALUES ('" + cellId + "', '" + attId + "', true, now())");
        }

        RestClient.Builder krokiBuilder = RestClient.builder();
        MockRestServiceServer krokiServer = MockRestServiceServer.bindTo(krokiBuilder).build();
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a};
        krokiServer.expect(requestTo("http://kroki.test/mermaid/png"))
                .andRespond(withSuccess(pngBytes, MediaType.IMAGE_PNG));

        AttachmentProperties props = new AttachmentProperties();
        props.setEnabled(true);
        props.setKrokiUrl("http://kroki.test");
        props.setKrokiTimeoutSeconds(5);
        props.setVistierieToken(""); // disable vision

        KrokiClient kroki = new KrokiClient(props, krokiBuilder, false);
        VisionClient vision = mock(VisionClient.class);
        when(vision.isEnabled()).thenReturn(false);

        AttachmentRepository repo = new AttachmentRepository(dsl);
        WriteToolService writeService = buildWriteService();
        AttachmentEnrichmentService svc = new AttachmentEnrichmentService(
                props, kroki, vision, seaweed, repo, writeService, dsl,
                new ExtractionProfileRegistry(),
                new VisionBudgetTracker(dsl, props.getVisionDailyBudgetUsd()));

        svc.renderAndStore(attId, cellId, fileHash, "text/x-mermaid", "graph TD; A-->B");

        var row = dsl.fetchOne("SELECT s3_key_thumbnail FROM attachments WHERE id = ?", attId);
        assertNotNull(row);
        assertEquals(fileHash + "-thumb.png", row.get("s3_key_thumbnail", String.class));

        var cellRow = dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", cellId);
        java.sql.Array tagsArr = cellRow.get("tags", java.sql.Array.class);
        Object[] tags = (Object[]) tagsArr.getArray();
        for (Object t : tags) assertNotEquals("kroki_pending", t);

        verify(seaweed).uploadBytes(eq(fileHash + "-thumb.png"), eq(pngBytes), eq("image/png"));
    }

    @Test
    void visionFlow_revisesCellContent_andRecordsBudget() throws Exception {
        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        String fileHash = "img456";
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, "
                    + "s3_key_original, uploaded_by) VALUES ('" + attId + "', '" + fileHash + "', "
                    + "'image/png', 'photo.png', 1024, '" + fileHash + ".png', 'tester')");
            st.execute("INSERT INTO cells (id, content, realm, signal, status, tags, embedding, created_at, valid_from) "
                    + "VALUES ('" + cellId + "', 'photo.png', 'test', 'facts', 'committed', "
                    + "ARRAY['vision_pending']::text[], NULL, now(), now())");
            st.execute("INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source, created_at) "
                    + "VALUES ('" + cellId + "', '" + attId + "', true, now())");
        }

        MockVistierieServer visionMock = new MockVistierieServer();
        visionMock.start();
        try {
        visionMock.stubVision("A whiteboard photo with handwritten notes about authentication.");

        RestClient visionHttp = RestClient.builder()
                .baseUrl(visionMock.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();

        AttachmentProperties props = new AttachmentProperties();
        props.setEnabled(true);
        props.setKrokiUrl(""); // disable kroki
        props.setVistierieToken("k");
        props.setVisionTimeoutSeconds(5);
        props.setVisionDailyBudgetUsd(1.0);
        props.setVisionMaxInputBytes(10 * 1024 * 1024);

        KrokiClient kroki = mock(KrokiClient.class);
        when(kroki.isEnabled()).thenReturn(false);
        VisionClient vision = new VisionClient(visionHttp, "k", props.getVisionMaxInputBytes());

        AttachmentRepository repo = new AttachmentRepository(dsl);
        WriteToolService writeService = buildWriteService();
        AttachmentEnrichmentService svc = new AttachmentEnrichmentService(
                props, kroki, vision, seaweed, repo, writeService, dsl,
                new ExtractionProfileRegistry(),
                new VisionBudgetTracker(dsl, props.getVisionDailyBudgetUsd()));

        svc.describeAndRevise(attId, cellId, fileHash + ".png", "image/png");

        var cellRow = dsl.fetchOne(
                "SELECT content, tags FROM cells WHERE valid_until IS NULL "
                        + "AND content LIKE 'A whiteboard photo%' ORDER BY created_at DESC LIMIT 1");
        assertNotNull(cellRow);
        assertTrue(cellRow.get("content", String.class)
                .startsWith("A whiteboard photo with handwritten notes"));

        var usageRow = dsl.fetchOne("SELECT total_calls, total_input_tokens FROM vision_usage");
        assertEquals(1, usageRow.get("total_calls", Integer.class));
        assertEquals(50, usageRow.get("total_input_tokens", Integer.class));
        } finally {
            visionMock.stop();
        }
    }

    private WriteToolService buildWriteService() throws Exception {
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
        ApplicationEventPublisher noop = e -> {};
        return new WriteToolService(writeRepo, new FixedEmbeddingClient(),
                opLogWriter, pushDispatcher, noop, new CellSelectorRepository(dsl));
    }
}
