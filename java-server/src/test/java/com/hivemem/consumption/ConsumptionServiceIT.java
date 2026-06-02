package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.attachment.KrokiClient;
import com.hivemem.attachment.ParserRegistry;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.attachment.TextAttachmentParser;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import com.hivemem.write.WriteToolRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class ConsumptionServiceIT {

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
    private AttachmentService attachments;

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

        // Build SeaweedFsClient
        AttachmentProperties attachmentProps = new AttachmentProperties();
        attachmentProps.setEnabled(true);
        attachmentProps.setS3Endpoint("http://" + S3.getHost() + ":" + S3.getMappedPort(8333));
        attachmentProps.setS3Bucket("hivemem-attachments");
        attachmentProps.setS3AccessKey("");
        attachmentProps.setS3SecretKey("");
        SeaweedFsClient seaweed = new SeaweedFsClient(attachmentProps);
        // invoke @PostConstruct init() via reflection
        var init = SeaweedFsClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(seaweed);

        // Build collaborators
        AttachmentRepository attachmentRepo = new AttachmentRepository(dsl);
        WriteToolRepository writeRepo = new WriteToolRepository(dsl);
        FixedEmbeddingClient embedding = new FixedEmbeddingClient();

        InstanceConfig instanceConfig = new InstanceConfig(dsl);
        var initMethod = InstanceConfig.class.getDeclaredMethod("initialize");
        initMethod.setAccessible(true);
        initMethod.invoke(instanceConfig);

        OpLogWriter opLogWriter = new OpLogWriter(dsl, instanceConfig, new ObjectMapper());

        SyncPeerRepository peerRepo = mock(SyncPeerRepository.class);
        SyncOpsRepository opsRepo = mock(SyncOpsRepository.class);
        PeerClient peerClient = mock(PeerClient.class);
        PushDispatcher pushDispatcher = new PushDispatcher(peerRepo, opsRepo, peerClient, instanceConfig);

        ApplicationEventPublisher noopPublisher = event -> {};

        // ParserRegistry with just a TextAttachmentParser (sufficient for plain .txt files)
        ParserRegistry parsers = new ParserRegistry(List.of(new TextAttachmentParser()));

        // KrokiClient — mock it; supports() returns false by default so the plain text path will not hit it
        KrokiClient krokiClient = mock(KrokiClient.class);

        attachments = new AttachmentService(
                attachmentProps, seaweed, parsers, attachmentRepo, writeRepo,
                embedding, dsl, noopPublisher, krokiClient);
    }

    @Test
    void consumesPlainTextFileAsCommittedCell(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("note.txt"), "Rechnung Acme GmbH Betrag 42 EUR");

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");
        ConsumptionService svc = new ConsumptionService(cp, attachments);

        svc.ingestFile(file);

        assertFalse(Files.exists(file), "source file should be moved away");
        assertTrue(Files.exists(root.resolve("processed").resolve("note.txt")),
                "file should land in processed/");

        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT realm, status, source FROM cells ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected at least one cell row");
            assertEquals("documents", rs.getString("realm"));
            assertEquals("committed", rs.getString("status"));
            assertTrue(rs.getString("source").startsWith("consumption:"),
                    "source should start with 'consumption:' but was: " + rs.getString("source"));
        }
    }
}
