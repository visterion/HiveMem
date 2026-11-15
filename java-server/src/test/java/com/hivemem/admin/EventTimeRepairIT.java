package com.hivemem.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Restores event times that revisions replaced before revisions started to keep them. Lineages are
 * seeded with plain SQL so created_at, valid_from, valid_until, parent_id and status are exact; an
 * "old-rule revision" is a child whose valid_from equals its own created_at and whose parent's
 * valid_until is that same instant.
 */
@Testcontainers
class EventTimeRepairIT {

    private static final String D = "2023-06-15T00:00:00Z";
    private static final String X = "2023-12-24T00:00:00Z";
    private static final String F = "2030-01-01T00:00:00Z";
    private static final String ROOT_DAY = "2024-01-01T00:00:00Z";

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private final AuthPrincipal principal = new AuthPrincipal("test-writer", AuthRole.WRITER);
    private DSLContext dsl;
    private WriteToolService writeService;
    private EventTimeRepairRepository repository;
    private EventTimeRepairService service;
    private OpLogWriter opLogWriter;
    /** Candidate cell id -> expected restored valid_from. */
    private final Map<UUID, Instant> expected = new TreeMap<>();

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM instance_identity");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) "
                + "VALUES (1, '00000000-0000-0000-0000-000000000001')");

        opLogWriter = new OpLogWriter(dsl, new StubInstanceConfig(), new ObjectMapper());
        writeService = new WriteToolService(
                new WriteToolRepository(dsl),
                new FixedEmbeddingClient(),
                opLogWriter,
                new NoopPushDispatcher(),
                (ApplicationEventPublisher) event -> { },
                null,
                new KgEntityRepository(dsl));
        repository = new EventTimeRepairRepository(dsl);
        service = new EventTimeRepairService(repository, opLogWriter, new NoopPushDispatcher());
        seedLineages();
    }

    @Test
    void previewReportsCandidatesAndWritesNothing() {
        Map<UUID, Instant> before = validFromByCell();
        int opsBefore = opsCount();

        Map<String, Object> result = service.preview(200);

        assertThat(result.keySet()).containsExactly("dry_run", "candidates", "by_status", "shift", "cells");
        assertThat(result.get("dry_run")).isEqualTo(true);
        assertThat(result.get("candidates")).isEqualTo(8);
        assertThat(map(result.get("by_status")))
                .containsEntry("committed", 6).containsEntry("rejected", 1).containsEntry("pending", 1)
                .hasSize(3);
        assertThat(map(result.get("shift")))
                .containsEntry("over_1_hour", 7).containsEntry("over_1_day", 7)
                .containsEntry("over_30_days", 7).containsEntry("over_365_days", 1)
                .containsEntry("event_time_later", 1);
        List<Map<String, Object>> cells = cells(result);
        assertThat(cells).extracting(c -> UUID.fromString((String) c.get("cell_id")))
                .containsExactlyElementsOf(expected.keySet());
        for (Map<String, Object> c : cells) {
            UUID id = UUID.fromString((String) c.get("cell_id"));
            assertThat(instant(c.get("new_valid_from"))).isEqualTo(expected.get(id));
            assertThat(instant(c.get("old_valid_from"))).isEqualTo(before.get(id));
        }
        assertThat(validFromByCell()).isEqualTo(before);
        assertThat(opsCount()).isEqualTo(opsBefore);
    }

    @Test
    void previewRespectsLimitAndOrder() {
        Map<String, Object> result = service.preview(2);

        assertThat(result.get("candidates")).isEqualTo(8);
        List<UUID> firstTwo = expected.keySet().stream().limit(2).toList();
        assertThat(cells(result)).extracting(c -> UUID.fromString((String) c.get("cell_id")))
                .containsExactlyElementsOf(firstTwo);
    }

    @Test
    void repairUpdatesExactlyTheCandidates() {
        Map<UUID, Instant> before = validFromByCell();
        int opsBefore = opsCount();

        Map<String, Object> result = service.repair(200);

        assertThat(result.get("dry_run")).isEqualTo(false);
        assertThat(result.get("updated")).isEqualTo(8);
        assertThat(result.get("skipped")).isEqualTo(0);
        assertThat(result.get("failed")).isEqualTo(0);
        assertThat(result.get("remaining")).isEqualTo(0);
        assertThat(cells(result)).hasSize(8);
        Map<UUID, Instant> after = validFromByCell();
        for (Map.Entry<UUID, Instant> e : after.entrySet()) {
            Instant want = expected.getOrDefault(e.getKey(), before.get(e.getKey()));
            assertThat(e.getValue()).as("valid_from of %s", e.getKey()).isEqualTo(want);
        }
        assertThat(opsCount()).isEqualTo(opsBefore + 8);
        List<Record> ops = dsl.fetch(
                "SELECT payload->>'cell_id' AS cell_id, payload->>'valid_from' AS valid_from, "
                        + "payload->>'agent_id' AS agent_id FROM ops_log WHERE op_type = 'update_cell_meta' "
                        + "ORDER BY payload->>'cell_id'");
        assertThat(ops).hasSize(8);
        List<UUID> ids = new ArrayList<>(expected.keySet());
        for (int i = 0; i < ops.size(); i++) {
            Record op = ops.get(i);
            UUID id = ids.get(i);
            assertThat(op.get("cell_id", String.class)).isEqualTo(id.toString());
            assertThat(instant(op.get("valid_from", String.class))).isEqualTo(expected.get(id));
            assertThat(op.get("agent_id", String.class)).isEqualTo("system-repair");
        }
    }

    @Test
    void secondRunFindsNothing() {
        service.repair(200);
        int opsAfterFirst = opsCount();

        assertThat(service.preview(200).get("candidates")).isEqualTo(0);
        Map<String, Object> second = service.repair(200);

        assertThat(second.get("updated")).isEqualTo(0);
        assertThat(opsCount()).isEqualTo(opsAfterFirst);
    }

    @Test
    void repairedCellRevisedAgainIsNotACandidate() {
        service.repair(200);
        UUID repaired = id(21);

        Map<String, Object> revised = writeService.reviseCell(principal, repaired, "again", "again summary");
        UUID newId = UUID.fromString(revised.get("new_id").toString());

        assertThat(service.preview(200).get("candidates")).isEqualTo(0);
        assertThat(validFromByCell().get(newId)).isEqualTo(expected.get(repaired));
    }

    @Test
    void listedOldValuesRestoreThePreviousState() {
        Map<UUID, Instant> seeded = validFromByCell();

        Map<String, Object> result = service.repair(200);
        for (Map<String, Object> c : cells(result)) {
            dsl.execute("UPDATE cells SET valid_from = ?::timestamptz WHERE id = ?",
                    (String) c.get("old_valid_from"), UUID.fromString((String) c.get("cell_id")));
        }

        assertThat(validFromByCell()).isEqualTo(seeded);
    }

    @Test
    void candidateChangedSinceTheQueryIsNotOverwritten() {
        EventTimeCandidate candidate = repository.findCandidates().stream()
                .filter(c -> c.cellId().equals(id(21))).findFirst().orElseThrow();
        dsl.execute("UPDATE cells SET valid_from = '2024-02-05T00:00:00Z' WHERE id = ?", id(21));

        int changed = repository.compareAndSetValidFrom(
                candidate.cellId(), candidate.oldValidFrom(), candidate.newValidFrom());

        assertThat(changed).isEqualTo(0);
        assertThat(validFromByCell().get(id(21))).isEqualTo(Instant.parse("2024-02-05T00:00:00Z"));
    }

    @Test
    void serviceSkipsAStaleCandidateAndAppendsNoOpForIt() {
        List<EventTimeCandidate> stale = repository.findCandidates();
        dsl.execute("UPDATE cells SET valid_from = '2024-02-05T00:00:00Z' WHERE id = ?", id(21));
        EventTimeRepairRepository staleOnce = new EventTimeRepairRepository(dsl) {
            private boolean served;

            @Override
            public List<EventTimeCandidate> findCandidates() {
                if (!served) {
                    served = true;
                    return stale;
                }
                return super.findCandidates();
            }
        };
        int opsBefore = opsCount();

        Map<String, Object> result =
                new EventTimeRepairService(staleOnce, opLogWriter, new NoopPushDispatcher()).repair(200);

        assertThat(result.get("updated")).isEqualTo(7);
        assertThat(result.get("skipped")).isEqualTo(1);
        assertThat(validFromByCell().get(id(21))).isEqualTo(Instant.parse("2024-02-05T00:00:00Z"));
        assertThat(opsCount()).isEqualTo(opsBefore + 7);
        assertThat(dsl.fetchOne("SELECT count(*) AS n FROM ops_log WHERE payload->>'cell_id' = ?",
                id(21).toString()).get("n", Integer.class)).isEqualTo(0);
    }

    @Test
    void aFailingCellDoesNotAbortTheRun() {
        Map<UUID, Instant> before = validFromByCell();
        EventTimeRepairRepository failingOnce = new EventTimeRepairRepository(dsl) {
            @Override
            public int compareAndSetValidFrom(UUID cellId, OffsetDateTime expectedOld, OffsetDateTime newValue) {
                if (cellId.equals(id(21))) {
                    throw new IllegalStateException("synthetic failure");
                }
                return super.compareAndSetValidFrom(cellId, expectedOld, newValue);
            }
        };
        int opsBefore = opsCount();

        Map<String, Object> result =
                new EventTimeRepairService(failingOnce, opLogWriter, new NoopPushDispatcher()).repair(200);

        assertThat(result.keySet())
                .containsExactly("dry_run", "updated", "skipped", "failed", "remaining", "cells");
        assertThat(result.get("updated")).isEqualTo(7);
        assertThat(result.get("skipped")).isEqualTo(0);
        assertThat(result.get("failed")).isEqualTo(1);
        assertThat(result.get("remaining")).isEqualTo(1);
        assertThat(cells(result)).extracting(c -> UUID.fromString((String) c.get("cell_id")))
                .doesNotContain(id(21)).hasSize(7);
        assertThat(validFromByCell().get(id(21))).isEqualTo(before.get(id(21)));
        assertThat(opsCount()).isEqualTo(opsBefore + 7);
        assertThat(dsl.fetchOne("SELECT count(*) AS n FROM ops_log WHERE payload->>'cell_id' = ?",
                id(21).toString()).get("n", Integer.class)).isEqualTo(0);
        assertThat(repository.findCandidates()).extracting(EventTimeCandidate::cellId).containsExactly(id(21));
    }

    // ── seeding ────────────────────────────────────────────────────────────

    private void seedLineages() {
        // L1: root only, explicit date.
        seed(1, null, "2024-01-01T00:00:00Z", D, null, "committed");
        // L2: root without explicit date -> old-rule revision.
        seed(2, null, ROOT_DAY, ROOT_DAY, "2024-02-01T00:00:00Z", "committed");
        seed(21, 2, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "committed");
        expect(21, ROOT_DAY);
        // L3: root with date D -> old-rule revision.
        seed(3, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(31, 3, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "committed");
        expect(31, D);
        // L4: root (D) -> old-rule revision -> revision through reviseCell (copies the wrong date).
        seed(4, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(41, 4, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "committed");
        revise(41, 42);
        expect(42, D);
        // L5: root -> old-rule revision re-dated to X -> old-rule revision -> reviseCell revision.
        seed(5, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(51, 5, "2024-02-01T00:00:00Z", X, "2024-03-01T00:00:00Z", "committed");
        seed(52, 51, "2024-03-01T00:00:00Z", "2024-03-01T00:00:00Z", null, "committed");
        revise(52, 53);
        expect(53, X);
        // L6: root (D) -> revision through reviseCell only.
        seed(6, null, ROOT_DAY, D, null, "committed");
        revise(6, 61);
        // L7 / L8: old-rule revision whose current row is rejected / pending.
        seed(7, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(71, 7, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "rejected");
        expect(71, D);
        seed(8, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(81, 8, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "pending");
        expect(81, D);
        // L9: root with a future date.
        seed(9, null, ROOT_DAY, F, "2024-02-01T00:00:00Z", "committed");
        seed(91, 9, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", null, "committed");
        expect(91, F);
        // L10: depth 6 of old-rule revisions.
        String[] months = {"2024-02-01", "2024-03-01", "2024-04-01", "2024-05-01", "2024-06-01", "2024-07-01"};
        seed(10, null, ROOT_DAY, "2023-01-01T00:00:00Z", months[0] + "T00:00:00Z", "committed");
        for (int i = 0; i < months.length; i++) {
            int parent = i == 0 ? 10 : 100 + i;
            String until = i + 1 < months.length ? months[i + 1] + "T00:00:00Z" : null;
            seed(101 + i, parent, months[i] + "T00:00:00Z", months[i] + "T00:00:00Z", until, "committed");
        }
        expect(106, "2023-01-01T00:00:00Z");
        // L11: every row closed.
        seed(11, null, ROOT_DAY, D, "2024-02-01T00:00:00Z", "committed");
        seed(111, 11, "2024-02-01T00:00:00Z", "2024-02-01T00:00:00Z", "2024-03-01T00:00:00Z", "committed");
    }

    private void seed(int n, Integer parent, String createdAt, String validFrom, String validUntil, String status) {
        dsl.execute("INSERT INTO cells (id, parent_id, content, realm, signal, status, created_at, valid_from, "
                        + "valid_until) VALUES (?, ?, 'synthetic content', 'notes', 'facts', ?, "
                        + "?::timestamptz, ?::timestamptz, ?::timestamptz)",
                id(n), parent == null ? null : id(parent), status, createdAt, validFrom, validUntil);
    }

    /** Revises through the write service (new rule) and renames the new row to the id of cell n. */
    private void revise(int fromN, int toN) {
        Map<String, Object> result = writeService.reviseCell(principal, id(fromN), "revised", "revised summary");
        UUID created = UUID.fromString(result.get("new_id").toString());
        dsl.execute("UPDATE cells SET id = ? WHERE id = ?", id(toN), created);
    }

    private void expect(int n, String validFrom) {
        expected.put(id(n), Instant.parse(validFrom));
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<UUID, Instant> validFromByCell() {
        Map<UUID, Instant> out = new LinkedHashMap<>();
        for (Record r : dsl.fetch("SELECT id, valid_from FROM cells ORDER BY id")) {
            out.put(r.get("id", UUID.class), r.get("valid_from", OffsetDateTime.class).toInstant());
        }
        return out;
    }

    private int opsCount() {
        return dsl.fetchOne("SELECT count(*) AS n FROM ops_log").get("n", Integer.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static Map<String, Object> map(Object value) {
        return cast(value);
    }

    private static List<Map<String, Object>> cells(Map<String, Object> result) {
        return cast(result.get("cells"));
    }

    private static Instant instant(Object text) {
        return OffsetDateTime.parse((String) text).toInstant();
    }

    private static final class StubInstanceConfig extends InstanceConfig {
        StubInstanceConfig() {
            super(null);
            try {
                Field field = InstanceConfig.class.getDeclaredField("instanceId");
                field.setAccessible(true);
                field.set(this, UUID.fromString("00000000-0000-0000-0000-000000000001"));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class NoopPushDispatcher extends PushDispatcher {
        NoopPushDispatcher() {
            super(null, null, null, null);
        }

        @Override
        public void dispatch(UUID opId) {
            // no-op for test
        }
    }
}
