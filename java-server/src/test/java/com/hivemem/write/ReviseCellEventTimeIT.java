package com.hivemem.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * A revision keeps the event time of the version it replaces: the new row's valid_from equals the
 * old row's, while valid_until of the old row and created_at of the new row carry the revision time.
 */
@Testcontainers
class ReviseCellEventTimeIT {

    private static final OffsetDateTime EVENT_TIME = OffsetDateTime.parse("2024-03-05T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private final AuthPrincipal principal = new AuthPrincipal("test-writer", AuthRole.WRITER);
    private DSLContext dsl;
    private WriteToolService service;

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

        service = new WriteToolService(
                new WriteToolRepository(dsl),
                new FixedEmbeddingClient(),
                new OpLogWriter(dsl, new StubInstanceConfig(), new ObjectMapper()),
                new NoopPushDispatcher(),
                (ApplicationEventPublisher) event -> { },
                null,
                new KgEntityRepository(dsl));
    }

    @Test
    void revisionKeepsExplicitEventTime() {
        UUID oldId = addCell(EVENT_TIME);

        UUID newId = revise(oldId);

        Record current = row(newId);
        assertThat(current.get("valid_from", OffsetDateTime.class).toInstant()).isEqualTo(EVENT_TIME.toInstant());
        assertThat(current.get("valid_until")).isNull();
        assertThat(current.get("created_at", OffsetDateTime.class)).isAfter(EVENT_TIME);
        assertThat(row(oldId).get("valid_until")).isNotNull();
    }

    @Test
    void secondRevisionKeepsTheFirstEventTime() {
        UUID first = addCell(EVENT_TIME);

        UUID second = revise(revise(first));

        assertThat(row(second).get("valid_from", OffsetDateTime.class).toInstant())
                .isEqualTo(EVENT_TIME.toInstant());
        assertThat(row(second).get("valid_until")).isNull();
    }

    @Test
    void revisionOfCellWithoutExplicitDateKeepsItsCreationDate() {
        UUID rootId = addCell(null);
        OffsetDateTime rootValidFrom = row(rootId).get("valid_from", OffsetDateTime.class);

        UUID newId = revise(rootId);

        assertThat(row(newId).get("valid_from", OffsetDateTime.class).toInstant())
                .isEqualTo(rootValidFrom.toInstant());
    }

    @Test
    void dateWrittenAfterRevisionWins() {
        UUID newId = revise(addCell(EVENT_TIME));
        OffsetDateTime later = OffsetDateTime.parse("2025-01-02T00:00:00Z");

        service.updateCellMeta(principal, newId, null, null, later, List.of());

        assertThat(row(newId).get("valid_from", OffsetDateTime.class).toInstant()).isEqualTo(later.toInstant());
    }

    @Test
    void reviseCellWithSummaryKeepsEventTime() {
        UUID oldId = addCell(EVENT_TIME);

        Map<String, Object> result = service.reviseCellWithSummary(
                principal, oldId, "revised content", "a summary", List.of("point"), "an insight", List.of("tag"));
        UUID newId = UUID.fromString(result.get("new_id").toString());

        assertThat(row(newId).get("valid_from", OffsetDateTime.class).toInstant())
                .isEqualTo(EVENT_TIME.toInstant());
    }

    private UUID addCell(OffsetDateTime validFrom) {
        Map<String, Object> result = service.addCell(principal, "original content", "notes", "facts", null, null,
                List.of(), null, "original summary", List.of(), null, null, "committed", validFrom, null);
        return UUID.fromString(result.get("id").toString());
    }

    private UUID revise(UUID oldId) {
        Map<String, Object> result = service.reviseCell(principal, oldId, "revised content", "revised summary");
        return UUID.fromString(result.get("new_id").toString());
    }

    private Record row(UUID id) {
        return dsl.fetchOne("SELECT valid_from, valid_until, created_at FROM cells WHERE id = ?", id);
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
