package com.hivemem.backup;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
public class BackupRestoreService {

    private final BackupProperties props;
    private final DSLContext dsl;
    private final SeaweedFsClient seaweed;
    private final String bucket;
    private final String dbJdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public BackupRestoreService(BackupProperties props,
                                DSLContext dsl,
                                SeaweedFsClient seaweed,
                                AttachmentProperties attachmentProps,
                                Environment env) {
        this.props = props;
        this.dsl = dsl;
        this.seaweed = seaweed;
        this.bucket = attachmentProps.getS3Bucket();
        this.dbJdbcUrl = env.getProperty("spring.datasource.url");
        this.dbUser = env.getProperty("spring.datasource.username");
        this.dbPassword = env.getProperty("spring.datasource.password");
    }

    /**
     * Restore an archive into the configured target database+bucket.
     *
     * @param mode  MOVE adopts the source identity; CLONE rotates it and clears sync state.
     * @param force when true, truncates target tables and bucket before import (otherwise refuses
     *              if target is non-empty).
     */
    public void restore(InputStream archiveIn, RestoreMode mode, boolean force)
            throws IOException, InterruptedException {

        // Phase-1 simplification: we read the entire archive into memory because manifest.json is
        // written last by BackupService (after counts are known) and we need to read it before
        // we can validate. For very large archives, switch to a temp-file approach with two passes.
        byte[] all = archiveIn.readAllBytes();
        Manifest manifest = readManifest(all);
        ManifestValidator.validateBasics(manifest);
        ManifestValidator.validateFlywayMatch(manifest, currentFlywayVersion());

        S3Client s3 = seaweed.s3Client();
        EmptinessCheck check = new EmptinessCheck(dsl, s3, bucket);
        SyncStateHandler sync = new SyncStateHandler(dsl);
        UUID currentId = sync.currentInstanceId();

        if (mode == RestoreMode.MOVE && currentId != null
                && !currentId.equals(manifest.instanceId()) && !force) {
            throw new IllegalStateException(
                    "MOVE refused: target instance_identity " + currentId
                    + " differs from manifest " + manifest.instanceId()
                    + ". Use --force to override.");
        }

        boolean dbHasData = !check.dbEmpty();
        boolean bucketHasData = !check.bucketEmpty();
        if ((dbHasData || bucketHasData) && !force) {
            throw new IllegalStateException(
                    "Restore refused: target is not empty (db="
                    + dbHasData + ", bucket=" + bucketHasData + "). Use --force.");
        }

        // The dump COPYs into every application table — including singleton rows the restore
        // process itself seeds while booting (instance_identity via InstanceConfig, identity via
        // the embedding startup check). Always truncate the full dumped table set before the
        // import: the target is either verified empty of user data (above) or being force-wiped,
        // and skipping this makes the COPY hit PK conflicts on any previously-booted target.
        truncateAllHivememTables();
        if (force) {
            emptyBucket(s3);
        }

        // Stream entries: postgres.sql.gz → psql restore; attachments/* → S3 putObject.
        // IMPORTANT: e.stream() IS the TarArchiveInputStream; do not close it between entries.
        // Read compressed bytes first to avoid closing the tar stream via GZIPInputStream.close().
        long restoredObjects = 0;
        try (var br = new ArchiveReader(new ByteArrayInputStream(all))) {
            ArchiveReader.Entry e;
            while ((e = br.nextEntry()) != null) {
                if (e.name().equals("postgres.sql.gz")) {
                    byte[] gzBytes = e.read();
                    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
                        new PostgresRestorer(props.getPsqlPath())
                                .restore(dbJdbcUrl, dbUser, dbPassword, gz);
                    }
                } else if (e.name().startsWith("attachments/")) {
                    String key = e.name().substring("attachments/".length());
                    new SeaweedFSRestorer(s3, bucket).put(key, e.stream(), e.size());
                    restoredObjects++;
                }
                // manifest.json is read in pass-1 above; ignore here.
            }
        }

        verifyAgainstManifest(manifest, restoredObjects);

        if (mode == RestoreMode.CLONE) {
            sync.applyClone();
        }
    }

    /**
     * Fail loudly when the restored state does not match the manifest the archive itself
     * declared — a truncated or partially-written archive must not restore "successfully"
     * with silently-missing data.
     */
    private void verifyAgainstManifest(Manifest manifest, long restoredObjects) {
        Manifest.Counts expected = manifest.counts();
        long cells = countRows("cells");
        long attachments = countRows("attachments");
        long facts = countRows("facts");
        long tunnels = countRows("tunnels");
        if (cells != expected.cells() || attachments != expected.attachments()
                || facts != expected.facts() || tunnels != expected.tunnels()) {
            throw new IllegalStateException(String.format(
                    "Restore verification failed: DB counts differ from manifest "
                    + "(cells %d/%d, attachments %d/%d, facts %d/%d, tunnels %d/%d)",
                    cells, expected.cells(), attachments, expected.attachments(),
                    facts, expected.facts(), tunnels, expected.tunnels()));
        }
        long expectedObjects = manifest.attachments().objectCount();
        if (restoredObjects != expectedObjects) {
            throw new IllegalStateException("Restore verification failed: restored "
                    + restoredObjects + " attachment objects but manifest lists " + expectedObjects);
        }
    }

    private long countRows(String table) {
        return dsl.fetchOne("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    private Manifest readManifest(byte[] archive) throws IOException {
        try (var r = new ArchiveReader(new ByteArrayInputStream(archive))) {
            ArchiveReader.Entry e;
            while ((e = r.nextEntry()) != null) {
                if (e.name().equals("manifest.json")) {
                    String json = new String(e.read(), StandardCharsets.UTF_8);
                    return ManifestCodec.fromJson(json);
                }
            }
        }
        throw new IllegalStateException("manifest.json missing in archive");
    }

    private String currentFlywayVersion() {
        var rec = dsl.fetchOptional(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1");
        return rec.map(r -> "V" + r.get("version", String.class)).orElse("V0000");
    }

    private void truncateAllHivememTables() {
        // Truncate the FULL dumped table set: pg_dump --data-only covers every application table
        // (only flyway_schema_history/migration_baseline are excluded — mirror that here), so
        // any table left out (previously instance_identity, identity, agents, references_,
        // blueprints, agent_diary, …) makes the single-transaction import fail on PK conflicts
        // AFTER these truncates already committed. Enumerating pg_tables keeps the set in sync
        // with future migrations automatically.
        List<String> tables = dsl.fetch("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename NOT IN ('flyway_schema_history', 'migration_baseline')
                """).map(r -> r.get("tablename", String.class));
        if (tables.isEmpty()) {
            return;
        }
        String joined = tables.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", "));
        dsl.execute("TRUNCATE " + joined + " RESTART IDENTITY CASCADE");
    }

    private void emptyBucket(S3Client s3) {
        // listObjectsV2 pages at 1000 keys — loop on the continuation token, otherwise a
        // --force restore leaves stale attachments behind in buckets with >1000 objects.
        String continuationToken = null;
        do {
            var resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .continuationToken(continuationToken)
                    .build());
            for (var obj : resp.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
            }
            continuationToken = Boolean.TRUE.equals(resp.isTruncated())
                    ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
    }
}
