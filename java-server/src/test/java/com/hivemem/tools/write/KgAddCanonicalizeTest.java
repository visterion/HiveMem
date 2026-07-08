package com.hivemem.tools.write;

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
import java.util.List;
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

@Testcontainers
class KgAddCanonicalizeTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private WriteToolService svc;
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
        dsl.execute("DELETE FROM kg_entity");

        dsl.execute("DELETE FROM instance_identity");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, '00000000-0000-0000-0000-000000000001')");

        WriteToolRepository repo = new WriteToolRepository(dsl);
        FixedEmbeddingClient embedding = new FixedEmbeddingClient();
        InstanceConfig config = new MockInstanceConfig();
        OpLogWriter opLog = new OpLogWriter(dsl, config, new ObjectMapper());
        PushDispatcher push = new MockPushDispatcher();
        ApplicationEventPublisher events = new MockApplicationEventPublisher();
        KgEntityRepository entities = new KgEntityRepository(dsl);
        entities.upsert("HiveMem", List.of("hivemem-mcp-server"), "tester");

        svc = new WriteToolService(repo, embedding, opLog, push, events, null, entities);
    }

    @Test
    void kgAddStoresCanonicalSubjectForAlias() {
        // registry seeded in setUp: HiveMem <- hivemem-mcp-server
        svc.kgAdd(principal, "hivemem-mcp-server", "tool_count", "49", 1.0, null, null, null, "insert");
        long canonical = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ? AND predicate = ?",
                "HiveMem", "tool_count").get("n", Long.class);
        assertThat(canonical).isEqualTo(1);
        long rawAlias = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ?",
                "hivemem-mcp-server").get("n", Long.class);
        assertThat(rawAlias).isZero();
    }

    @Test
    void supersedeAcrossAliasCollapsesToOneActiveFact() {
        svc.kgAdd(principal, "HiveMem", "tool_count", "49", 1.0, null, null, null, "insert");
        Map<String, Object> res = svc.kgAdd(principal, "hivemem-mcp-server", "tool_count", "50", 1.0, null, null, null, "supersede");
        assertThat(res).containsEntry("superseded", 1);
        long active = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE predicate = ?", "tool_count").get("n", Long.class);
        assertThat(active).isEqualTo(1);
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
