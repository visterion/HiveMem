package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.attachment.ExifExtractor;
import com.hivemem.attachment.ImageMetaRepository;
import com.hivemem.attachment.KrokiClient;
import com.hivemem.attachment.ParserRegistry;
import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.attachment.TextAttachmentParser;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.ocr.OcrProperties;
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
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;

/**
 * Shared Testcontainers + wiring for the consumption integration tests
 * (Postgres pgvector + SeaweedFS, Flyway migrate, real AttachmentService).
 */
@Testcontainers
abstract class ConsumptionITSupport {

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

    protected DSLContext dsl;
    protected AttachmentService attachments;
    protected SeaweedFsClient seaweed;
    protected SeparationJobRepository jobRepo;

    @BeforeEach
    void setUpSupport() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);

        // Clean state (tunnels first — they FK-reference cells)
        dsl.execute("DELETE FROM tunnels");
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM attachments");
        dsl.execute("DELETE FROM consumption_jobs");
        dsl.execute("DELETE FROM consumption_file");
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");

        // Build SeaweedFsClient
        AttachmentProperties attachmentProps = new AttachmentProperties();
        attachmentProps.setEnabled(true);
        attachmentProps.setS3Endpoint("http://" + S3.getHost() + ":" + S3.getMappedPort(8333));
        attachmentProps.setS3Bucket("hivemem-attachments");
        attachmentProps.setS3AccessKey("");
        attachmentProps.setS3SecretKey("");
        seaweed = new SeaweedFsClient(attachmentProps);
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

        // ParserRegistry with just a TextAttachmentParser (sufficient for plain .txt files; PDF parsing
        // is not required because the apply()/dispatch() paths ingest raw PDF bytes).
        ParserRegistry parsers = new ParserRegistry(List.of(new TextAttachmentParser()));

        // KrokiClient — mock it; supports() returns false by default
        KrokiClient krokiClient = mock(KrokiClient.class);

        ExifExtractor exifExtractor = new ExifExtractor();
        ImageMetaRepository imageMetaRepo = new ImageMetaRepository(dsl);

        attachments = new AttachmentService(
                attachmentProps, seaweed, parsers, attachmentRepo, writeRepo,
                embedding, dsl, noopPublisher, krokiClient, exifExtractor, imageMetaRepo);

        jobRepo = new SeparationJobRepository(dsl);
    }

    /** Wrap an instance (possibly null) in an ObjectProvider for constructor wiring in tests. */
    protected static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return instance; }
            @Override public T getObject() { return instance; }
            @Override public T getIfAvailable() { return instance; }
            @Override public T getIfUnique() { return instance; }
            @Override public Stream<T> stream() { return instance == null ? Stream.empty() : Stream.of(instance); }
        };
    }

    /** Build a ConsumptionService wired to the shared collaborators, with no VistierieSeparationClient
     *  (so the single-doc path is taken and apply() works without queen). No ledger repo. */
    protected ConsumptionService buildService(ConsumptionProperties cp) {
        return buildService(cp, null);
    }

    /** Build a ConsumptionService wired to the shared collaborators with an optional ledger repo. */
    protected ConsumptionService buildService(ConsumptionProperties cp, ConsumptionFileRepository fileRepo) {
        OcrProperties ocrProps = new OcrProperties();
        ObjectProvider<VistierieSeparationClient> nullProvider = new ObjectProvider<>() {
            @Override public VistierieSeparationClient getObject(Object... args) { return null; }
            @Override public VistierieSeparationClient getObject() { return null; }
            @Override public VistierieSeparationClient getIfAvailable() { return null; }
            @Override public VistierieSeparationClient getIfUnique() { return null; }
            @Override public Stream<VistierieSeparationClient> stream() { return Stream.empty(); }
        };
        ObjectProvider<VisionMultiClient> nullVisionProvider = new ObjectProvider<>() {
            @Override public VisionMultiClient getObject(Object... args) { return null; }
            @Override public VisionMultiClient getObject() { return null; }
            @Override public VisionMultiClient getIfAvailable() { return null; }
            @Override public VisionMultiClient getIfUnique() { return null; }
            @Override public Stream<VisionMultiClient> stream() { return Stream.empty(); }
        };
        ObjectProvider<ConsumptionFileRepository> fileRepoProvider = new ObjectProvider<>() {
            @Override public ConsumptionFileRepository getObject(Object... args) { return fileRepo; }
            @Override public ConsumptionFileRepository getObject() { return fileRepo; }
            @Override public ConsumptionFileRepository getIfAvailable() { return fileRepo; }
            @Override public ConsumptionFileRepository getIfUnique() { return fileRepo; }
            @Override public Stream<ConsumptionFileRepository> stream() { return fileRepo != null ? Stream.of(fileRepo) : Stream.empty(); }
        };
        ObjectProvider<CompleteClient> nullCompleteProvider = new ObjectProvider<>() {
            @Override public CompleteClient getObject(Object... args) { return null; }
            @Override public CompleteClient getObject() { return null; }
            @Override public CompleteClient getIfAvailable() { return null; }
            @Override public CompleteClient getIfUnique() { return null; }
            @Override public Stream<CompleteClient> stream() { return Stream.empty(); }
        };
        return new ConsumptionService(cp, attachments, ocrProps, seaweed, jobRepo, nullProvider, nullVisionProvider,
                nullCompleteProvider, fileRepoProvider);
    }
}
