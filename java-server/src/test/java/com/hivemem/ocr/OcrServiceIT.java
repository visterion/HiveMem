package com.hivemem.ocr;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.summarize.NeedsSummaryDecider;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class OcrServiceIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data")
            .withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(180)));

    private DSLContext dsl;
    private SeaweedFsClient seaweed;

    @BeforeEach
    void setUp() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);

        // Clean state
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM attachments");
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");

        AttachmentProperties attachmentProps = new AttachmentProperties();
        attachmentProps.setEnabled(true);
        attachmentProps.setS3Endpoint("http://" + S3.getHost() + ":" + S3.getMappedPort(8333));
        attachmentProps.setS3Bucket("hivemem-attachments");
        attachmentProps.setS3AccessKey("");  // anonymous — no S3 auth configured in SeaweedFS test container
        attachmentProps.setS3SecretKey("");
        seaweed = new SeaweedFsClient(attachmentProps);
        // invoke @PostConstruct init() via reflection
        var init = SeaweedFsClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(seaweed);
    }

    @Test
    void ocrsScannedPdf_writesTextToCell() throws Exception {
        // 1. Build "scan" PDF — text rendered as image, no text layer
        byte[] pdfBytes = buildScannedPdf("HELLO HIVEMEM");

        // 2. Upload to SeaweedFS
        String s3Key = "test/ocr-scan-" + UUID.randomUUID() + ".pdf";
        seaweed.uploadBytes(s3Key, pdfBytes, "application/pdf");

        // 3. Seed attachment + cell + join rows
        UUID cellId = UUID.randomUUID();
        UUID attId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, "
                    + "size_bytes, s3_key_original, uploaded_by, created_at) VALUES ('"
                    + attId + "', 'fakehash-" + UUID.randomUUID() + "', 'application/pdf', 'scan.pdf', "
                    + pdfBytes.length + ", '" + s3Key + "', 'test', now())");
            st.execute("INSERT INTO cells (id, content, realm, signal, status, tags, "
                    + "embedding, created_at, valid_from) VALUES ('"
                    + cellId + "', 'scan.pdf', 'test', 'facts', 'committed', "
                    + "ARRAY['ocr_pending']::text[], NULL, now(), now())");
            st.execute("INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source) "
                    + "VALUES ('" + cellId + "', '" + attId + "', true)");
        }

        // 4. Build OcrService stack (same pattern as SummarizerServiceIT)
        OcrProperties ocrProps = new OcrProperties();
        ocrProps.setEnabled(true);
        ocrProps.setLanguages("eng");
        ocrProps.setMaxPages(10);
        ocrProps.setRenderDpi(300);
        ocrProps.setCallTimeoutSeconds(60);

        OcrRepository ocrRepo = new OcrRepository(dsl);
        WriteToolRepository writeRepo = new WriteToolRepository(dsl);
        EmbeddingClient embedding = new FixedEmbeddingClient();

        InstanceConfig instanceConfig = new InstanceConfig(dsl);
        var initMethod = InstanceConfig.class.getDeclaredMethod("initialize");
        initMethod.setAccessible(true);
        initMethod.invoke(instanceConfig);

        OpLogWriter opLogWriter = new OpLogWriter(dsl, instanceConfig, new ObjectMapper());

        SyncPeerRepository peerRepo = mock(SyncPeerRepository.class);
        SyncOpsRepository opsRepo = mock(SyncOpsRepository.class);
        PeerClient peerClient = mock(PeerClient.class);
        PushDispatcher pushDispatcher = new PushDispatcher(peerRepo, opsRepo, peerClient, instanceConfig);

        ApplicationEventPublisher noopPublisher = e -> {};

        WriteToolService writeService = new WriteToolService(
                writeRepo, embedding, opLogWriter, pushDispatcher, noopPublisher, new CellSelectorRepository(dsl),
                new com.hivemem.kg.KgEntityRepository(dsl));

        OcrService service = new OcrService(
                ocrProps, ocrRepo, seaweed, writeService,
                /*visionClient*/ null, /*attachmentProps*/ null, /*dsl*/ null, /*dedup*/ null);

        // 5. Run OCR
        service.processOne(cellId, s3Key);

        // 6. Assert: new revision contains page marker and recognized text
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT content, 'ocr_pending' = ANY(tags) AS still_pending "
                             + "FROM cells WHERE valid_until IS NULL AND status = 'committed' "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected a revised cell row");
            String content = rs.getString("content");
            boolean stillPending = rs.getBoolean("still_pending");
            assertTrue(content.contains("[page=1]"),
                    "Expected page marker '[page=1]' in content, got: " + content);
            String upper = content.toUpperCase();
            assertTrue(upper.contains("HELLO") || upper.contains("HIVEMEM"),
                    "Expected OCR'd text ('HELLO' or 'HIVEMEM'), got: " + content);
            assertFalse(stillPending, "ocr_pending tag should have been removed");
        }
    }

    private static byte[] buildScannedPdf(String text) throws Exception {
        // Render text into a high-res image so OCR can read it clearly
        int w = 2480, h = 3508; // A4 at 300 DPI
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Monospaced", Font.BOLD, 200));
        g.drawString(text, 100, 500);
        g.dispose();

        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdImg = LosslessFactory.createFromImage(doc, img);
            // Fill the full page so rasterization yields a legible image
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImg, 0, 0,
                        page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
            }
            doc.save(pdfOut);
        }
        return pdfOut.toByteArray();
    }
}
