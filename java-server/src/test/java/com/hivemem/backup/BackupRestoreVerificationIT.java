package com.hivemem.backup;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduces the M11 bug: a manifest whose row counts were taken from the app's live DB
 * connection BEFORE pg_dump's own later snapshot can legitimately differ from what actually
 * gets imported (a live backup, main server still writing). That must not fail a good restore,
 * and a --mode=clone restore must still rotate the target's instance identity even when the
 * verification step encounters a count mismatch (or otherwise fails) — otherwise the target
 * keeps the source's instance_id/ops_log, which is exactly the split-brain CLONE exists to
 * prevent.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BackupRestoreVerificationIT.TestConfig.class)
class BackupRestoreVerificationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary EmbeddingClient embeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> SOURCE_DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> SOURCE_S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data").withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @Container
    static final PostgreSQLContainer<?> TARGET_DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> TARGET_S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data").withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @DynamicPropertySource
    static void wireSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SOURCE_DB::getJdbcUrl);
        r.add("spring.datasource.username", SOURCE_DB::getUsername);
        r.add("spring.datasource.password", SOURCE_DB::getPassword);
        r.add("hivemem.attachment.enabled", () -> "true");
        r.add("hivemem.attachment.s3-endpoint",
                () -> "http://" + SOURCE_S3.getHost() + ":" + SOURCE_S3.getMappedPort(8333));
    }

    @Autowired BackupService backup;

    @Test
    void cellCountMismatchLogsWarningAndCloneStillRotatesIdentity() throws Exception {
        UUID sourceInstanceId = seedSourceAndGetInstanceId();

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        backup.export(archiveOut);
        byte[] tampered = tamperManifestCounts(archiveOut.toByteArray(), +1, 0);

        migrateTarget();

        // Must NOT throw despite the manifest claiming one more cell than was actually
        // imported — this used to be a hard IllegalStateException.
        new BackupTestRestorer(TARGET_DB, TARGET_S3).restore(tampered, RestoreMode.CLONE);

        try (Connection c = DriverManager.getConnection(
                TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(), TARGET_DB.getPassword());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM cells")) {
                rs.next();
                assertEquals(2, rs.getInt(1)); // the actually-imported rows, restore completed
            }
            try (ResultSet rs = st.executeQuery("SELECT instance_id FROM instance_identity WHERE id = 1")) {
                rs.next();
                UUID newId = (UUID) rs.getObject(1);
                assertNotEquals(sourceInstanceId, newId); // applyClone() ran
            }
        }
    }

    @Test
    void attachmentObjectCountMismatchStillFailsButCloneIdentityIsAlreadyRotated() throws Exception {
        UUID sourceInstanceId = seedSourceAndGetInstanceId();

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        backup.export(archiveOut);
        // Tamper the attachment OBJECT count (S3 blobs), which must remain a hard failure —
        // it indicates a genuinely truncated/corrupt archive, unlike the DB row-count race.
        byte[] tampered = tamperManifestCounts(archiveOut.toByteArray(), 0, +1);

        migrateTarget();

        assertThrows(IllegalStateException.class,
                () -> new BackupTestRestorer(TARGET_DB, TARGET_S3).restore(tampered, RestoreMode.CLONE));

        // Even though verification ultimately failed, the CLONE identity rotation must have
        // already happened (it now runs BEFORE verification) — otherwise the target would be
        // left holding the source's instance_id, the split-brain CLONE exists to prevent.
        try (Connection c = DriverManager.getConnection(
                TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(), TARGET_DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT instance_id FROM instance_identity WHERE id = 1")) {
            rs.next();
            UUID newId = (UUID) rs.getObject(1);
            assertNotEquals(sourceInstanceId, newId);
        }
    }

    private UUID seedSourceAndGetInstanceId() throws Exception {
        try (Connection c = DriverManager.getConnection(
                SOURCE_DB.getJdbcUrl(), SOURCE_DB.getUsername(), SOURCE_DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from) "
                    + "VALUES "
                    + "(gen_random_uuid(), 'hello', array_fill(0::real, ARRAY[1024])::vector, 'test', 'facts', 'TestTopic', 'committed', 'test', now()),"
                    + "(gen_random_uuid(), 'world', array_fill(0::real, ARRAY[1024])::vector, 'test', 'facts', 'TestTopic', 'committed', 'test', now())");
        }
        try (Connection c = DriverManager.getConnection(
                SOURCE_DB.getJdbcUrl(), SOURCE_DB.getUsername(), SOURCE_DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT instance_id FROM instance_identity WHERE id = 1")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private void migrateTarget() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(), TARGET_DB.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
    }

    /** Rebuilds the archive with the manifest's cells/attachment-object counts bumped, keeping
     *  every other entry byte-for-byte identical. Simulates the live-backup race (or a
     *  genuinely truncated archive, for the attachment-object-count case). */
    private static byte[] tamperManifestCounts(byte[] archive, int cellsDelta, int objectsDelta) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<byte[]> datas = new java.util.ArrayList<>();
        try (ArchiveReader r = new ArchiveReader(new ByteArrayInputStream(archive))) {
            ArchiveReader.Entry e;
            while ((e = r.nextEntry()) != null) {
                names.add(e.name());
                datas.add(e.read());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArchiveWriter w = new ArchiveWriter(out)) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals("manifest.json")) {
                    Manifest m = ManifestCodec.fromJson(new String(datas.get(i), StandardCharsets.UTF_8));
                    Manifest.Counts c = m.counts();
                    Manifest.Attachments a = m.attachments();
                    Manifest tampered = new Manifest(
                            m.schemaVersion(), m.hivememBuild(), m.instanceId(), m.createdAt(), m.flywayVersion(),
                            new Manifest.Counts(c.cells() + cellsDelta, c.attachments(), c.facts(), c.tunnels()),
                            m.opsLog(), m.syncPeers(), m.appliedOps(), m.postgres(),
                            new Manifest.Attachments(a.s3Bucket(), a.objectCount() + objectsDelta, a.totalBytes()));
                    w.addEntry("manifest.json", ManifestCodec.toJson(tampered).getBytes(StandardCharsets.UTF_8));
                } else {
                    w.addEntry(names.get(i), datas.get(i));
                }
            }
        }
        return out.toByteArray();
    }
}
