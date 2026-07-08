package com.hivemem.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.InstanceConfig;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;

@Testcontainers
class KgRenameSupersedeTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private WriteToolService writeToolService;
    private final AuthPrincipal principal = new AuthPrincipal("test-writer", AuthRole.WRITER);

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM cells");

        dsl.execute("DELETE FROM instance_identity");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, '00000000-0000-0000-0000-000000000001')");

        WriteToolRepository repo = new WriteToolRepository(dsl);
        FixedEmbeddingClient embedding = new FixedEmbeddingClient();
        InstanceConfig config = new MockInstanceConfig();
        OpLogWriter opLog = new OpLogWriter(dsl, config, new ObjectMapper());
        PushDispatcher push = new MockPushDispatcher();
        ApplicationEventPublisher events = new MockApplicationEventPublisher();

        writeToolService = new WriteToolService(repo, embedding, opLog, push, events, null,
                new KgEntityRepository(dsl));
    }

    // ============ on_conflict = supersede ============

    @Test
    void supersedeWithConflictInvalidatesOldFactAndInsertsNew() {
        UUID oldId = addFact("HiveMem", "status", "beta", "insert");

        Map<String, Object> result = kgAdd("HiveMem", "status", "ga", "supersede");

        assertThat(result).containsEntry("inserted", true);
        assertThat(result).containsEntry("superseded", 1);

        Record oldRow = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", oldId);
        assertThat(oldRow.get("valid_until")).isNotNull();

        long activeCount = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ? AND predicate = ?",
                "HiveMem", "status").get("n", Long.class);
        assertThat(activeCount).isEqualTo(1);

        Record activeRow = dsl.fetchOne(
                "SELECT \"object\" FROM active_facts WHERE subject = ? AND predicate = ?",
                "HiveMem", "status");
        assertThat(activeRow.get("object", String.class)).isEqualTo("ga");
    }

    @Test
    void supersedeWithNoConflictInsertsNormally() {
        Map<String, Object> result = kgAdd("HiveMem", "status", "ga", "supersede");

        assertThat(result).containsEntry("inserted", true);
        assertThat(result).containsEntry("superseded", 0);
    }

    @Test
    void supersedeIsNowAValidOnConflictMode() {
        // Should not throw "Invalid on_conflict" for "supersede".
        Map<String, Object> result = kgAdd("X", "y", "z", "supersede");
        assertThat(result).containsEntry("inserted", true);
    }

    @Test
    void invalidOnConflictModeStillRejected() {
        assertThatThrownBy(() -> kgAdd("X", "y", "z", "bogus-mode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid on_conflict");
    }

    // ============ kg_rename_predicate ============

    @Test
    void renameBasicThreeFactsAcrossTwoSubjects() {
        OffsetDateTime validFrom1 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime validFrom2 = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        OffsetDateTime validFrom3 = OffsetDateTime.parse("2026-03-01T00:00:00Z");
        UUID f1 = addFactAt("hivemem", "repo", "github.com/a/hivemem", validFrom1);
        UUID f2 = addFactAt("hivemem-ui", "repo", "github.com/a/hivemem-ui", validFrom2);
        UUID f3 = addFactAt("hivemem", "repo", "github.com/a/hivemem-old", validFrom3);
        // f3 conflicts (same subject+predicate, different object) with f1's active row via insert
        // both stay active since we used plain "insert" (no supersede), so both are active.

        Map<String, Object> result = writeToolService.kgRenamePredicate(
                principal, "repo", "has_github_url", null, false);

        assertThat(result).containsEntry("renamed", 3);
        assertThat(result).containsEntry("matched", 3);

        for (UUID oldId : new UUID[] {f1, f2, f3}) {
            Record oldRow = dsl.fetchOne("SELECT valid_until, predicate FROM facts WHERE id = ?", oldId);
            assertThat(oldRow.get("valid_until")).isNotNull();
        }

        long oldPredicateActive = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE predicate = ?", "repo").get("n", Long.class);
        assertThat(oldPredicateActive).isEqualTo(0);

        long newPredicateActive = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE predicate = ?", "has_github_url")
                .get("n", Long.class);
        assertThat(newPredicateActive).isEqualTo(3);

        Record renamedF1 = dsl.fetchOne(
                "SELECT valid_from, status FROM active_facts WHERE subject = ? AND predicate = ? AND \"object\" = ?",
                "hivemem", "has_github_url", "github.com/a/hivemem");
        assertThat(renamedF1.get("valid_from", OffsetDateTime.class).isEqual(validFrom1)).isTrue();
        assertThat(renamedF1.get("status", String.class)).isEqualTo("committed");
    }

    @Test
    void renameWithSubjectFilterOnlyRenamesThatSubject() {
        addFact("hivemem", "repo", "github.com/a/hivemem", "insert");
        addFact("hivemem-ui", "repo", "github.com/a/hivemem-ui", "insert");

        Map<String, Object> result = writeToolService.kgRenamePredicate(
                principal, "repo", "has_github_url", "hivemem", false);

        assertThat(result).containsEntry("renamed", 1);
        assertThat(result).containsEntry("matched", 1);

        long hivememUiStillRepo = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ? AND predicate = ?",
                "hivemem-ui", "repo").get("n", Long.class);
        assertThat(hivememUiStillRepo).isEqualTo(1);

        long hivememRenamed = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ? AND predicate = ?",
                "hivemem", "has_github_url").get("n", Long.class);
        assertThat(hivememRenamed).isEqualTo(1);
    }

    @Test
    void renameOver201MatchesWithoutConfirmRequiresConfirm() {
        for (int i = 0; i < 201; i++) {
            addFact("subject-" + i, "bulkpred", "object-" + i, "insert");
        }

        assertThatThrownBy(() -> writeToolService.kgRenamePredicate(
                principal, "bulkpred", "renamed-pred", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirm");

        Map<String, Object> result = writeToolService.kgRenamePredicate(
                principal, "bulkpred", "renamed-pred", null, true);
        assertThat(result).containsEntry("renamed", 201);
        assertThat(result).containsEntry("matched", 201);
    }

    @Test
    void renameRejectsWhenFromEqualsTo() {
        assertThatThrownBy(() -> writeToolService.kgRenamePredicate(
                principal, "same", "same", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renameCoversPendingFactsAndPreservesStatus() {
        UUID pendingId = addFactWithStatus("hivemem", "legacy_pred", "pending-value", "pending");
        UUID committedId = addFactWithStatus("hivemem-ui", "legacy_pred", "committed-value", "committed");

        Map<String, Object> result = writeToolService.kgRenamePredicate(
                principal, "legacy_pred", "canonical_pred", null, false);

        assertThat(result).containsEntry("renamed", 2);
        assertThat(result).containsEntry("matched", 2);

        for (UUID oldId : new UUID[] {pendingId, committedId}) {
            Record oldRow = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", oldId);
            assertThat(oldRow.get("valid_until")).isNotNull();
        }

        Record renamedPending = dsl.fetchOne(
                "SELECT predicate, status FROM facts WHERE subject = ? AND \"object\" = ? AND valid_until IS NULL",
                "hivemem", "pending-value");
        assertThat(renamedPending.get("predicate", String.class)).isEqualTo("canonical_pred");
        assertThat(renamedPending.get("status", String.class)).isEqualTo("pending");

        Record renamedCommitted = dsl.fetchOne(
                "SELECT predicate, status FROM facts WHERE subject = ? AND \"object\" = ? AND valid_until IS NULL",
                "hivemem-ui", "committed-value");
        assertThat(renamedCommitted.get("predicate", String.class)).isEqualTo("canonical_pred");
        assertThat(renamedCommitted.get("status", String.class)).isEqualTo("committed");
    }

    private UUID addFactWithStatus(String subject, String predicate, String object, String status) {
        Map<String, Object> result = writeToolService.kgAdd(
                principal, subject, predicate, object, 0.95, null, status,
                OffsetDateTime.now(), "insert");
        return UUID.fromString(result.get("id").toString());
    }

    private UUID addFact(String subject, String predicate, String object, String onConflict) {
        Map<String, Object> result = writeToolService.kgAdd(
                principal, subject, predicate, object, 0.95, null, "committed",
                OffsetDateTime.now(), onConflict);
        return UUID.fromString(result.get("id").toString());
    }

    private UUID addFactAt(String subject, String predicate, String object, OffsetDateTime validFrom) {
        Map<String, Object> result = writeToolService.kgAdd(
                principal, subject, predicate, object, 0.95, null, "committed",
                validFrom, "insert");
        return UUID.fromString(result.get("id").toString());
    }

    private Map<String, Object> kgAdd(String subject, String predicate, String object, String onConflict) {
        return writeToolService.kgAdd(
                principal, subject, predicate, object, 0.95, null, "committed",
                OffsetDateTime.now(), onConflict);
    }

    static class MockInstanceConfig extends InstanceConfig {
        MockInstanceConfig() {
            super(null);
            try {
                Field field = InstanceConfig.class.getDeclaredField("instanceId");
                field.setAccessible(true);
                field.set(this, UUID.fromString("00000000-0000-0000-0000-000000000001"));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class MockPushDispatcher extends PushDispatcher {
        MockPushDispatcher() {
            super(null, null, null, null);
        }

        @Override
        public void dispatch(UUID opId) {
            // no-op for test
        }
    }

    static class MockApplicationEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(Object event) {
            // no-op for test
        }
    }
}
