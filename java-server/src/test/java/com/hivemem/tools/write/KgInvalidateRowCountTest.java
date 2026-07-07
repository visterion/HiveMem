package com.hivemem.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.InstanceConfig;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
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
import java.lang.reflect.Field;

@Testcontainers
class KgInvalidateRowCountTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private WriteToolService writeToolService;

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

        // Initialize instance identity for OpLogWriter
        dsl.execute("DELETE FROM instance_identity");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, '00000000-0000-0000-0000-000000000001')");

        WriteToolRepository repo = new WriteToolRepository(dsl);
        FixedEmbeddingClient embedding = new FixedEmbeddingClient();
        InstanceConfig config = new MockInstanceConfig();
        OpLogWriter opLog = new OpLogWriter(dsl, config, new ObjectMapper());
        PushDispatcher push = new MockPushDispatcher();
        ApplicationEventPublisher events = new MockApplicationEventPublisher();

        writeToolService = new WriteToolService(
                repo, embedding, opLog, push, events, null);
    }

    @Test
    void invalidateNonexistentFactReturnsFalse() {
        Map<String, Object> result = writeToolService.kgInvalidate(UUID.randomUUID());
        assertThat(result).containsEntry("invalidated", false);
    }

    @Test
    void invalidateActiveFactReturnsTrueThenFalseOnRepeat() {
        UUID factId = addFact();

        Map<String, Object> first = writeToolService.kgInvalidate(factId);
        assertThat(first).containsEntry("invalidated", true);

        Map<String, Object> second = writeToolService.kgInvalidate(factId);
        assertThat(second).containsEntry("invalidated", false);
    }

    @Test
    void noOpInvalidationEmitsNoSyncOp() {
        long before = countSyncOps("kg_invalidate");
        writeToolService.kgInvalidate(UUID.randomUUID());
        long after = countSyncOps("kg_invalidate");
        assertEquals(before, after, "no-op invalidation should not emit sync op");
    }

    private UUID addFact() {
        AuthPrincipal principal = new AuthPrincipal("test-agent", AuthRole.AGENT);
        Map<String, Object> result = writeToolService.kgAdd(
                principal,
                "subject",
                "predicate",
                "object",
                0.95,
                null,
                "pending",
                OffsetDateTime.now(),
                "insert"
        );
        return UUID.fromString(result.get("id").toString());
    }

    private long countSyncOps(String opType) {
        return dsl.fetchOne("SELECT count(*) AS n FROM ops_log WHERE op_type = ?", opType)
                .get("n", Long.class);
    }

    // Mock implementations for Spring dependencies
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
