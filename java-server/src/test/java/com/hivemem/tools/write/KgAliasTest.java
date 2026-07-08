package com.hivemem.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.InstanceConfig;
import java.util.List;
import java.util.Map;
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
import java.util.UUID;

@Testcontainers
class KgAliasTest {

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
        dsl.execute("DELETE FROM kg_entity");

        dsl.execute("DELETE FROM instance_identity");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, '00000000-0000-0000-0000-000000000001')");

        WriteToolRepository repo = new WriteToolRepository(dsl);
        FixedEmbeddingClient embedding = new FixedEmbeddingClient();
        InstanceConfig config = new MockInstanceConfig();
        OpLogWriter opLog = new OpLogWriter(dsl, config, new ObjectMapper());
        PushDispatcher push = new MockPushDispatcher();
        ApplicationEventPublisher events = new MockApplicationEventPublisher();

        svc = new WriteToolService(repo, embedding, opLog, push, events, null,
                new KgEntityRepository(dsl));
    }

    @Test
    void aliasRetroMigratesExistingFactsToCanonical() {
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) "
                + "VALUES ('hivemem-mcp-server', 'kind', 'server', 1.0, 'committed', 'seed')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) "
                + "VALUES ('HiveMem 9.0.0', 'kind', 'server', 1.0, 'committed', 'seed')");

        Map<String, Object> res = svc.kgAlias(principal, "HiveMem",
                List.of("hivemem-mcp-server", "HiveMem 9.0.0"), false);

        assertThat(res).containsEntry("registered", true);
        assertThat(res).containsEntry("migrated", 2);
        long canonical = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ?", "HiveMem").get("n", Long.class);
        assertThat(canonical).isEqualTo(2);
        long leftover = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject IN (?, ?)",
                "hivemem-mcp-server", "HiveMem 9.0.0").get("n", Long.class);
        assertThat(leftover).isZero();
    }

    @Test
    void aliasReportsResultingConflicts() {
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) "
                + "VALUES ('hivemem-mcp-server', 'tool_count', '49', 1.0, 'committed', 'seed')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) "
                + "VALUES ('HiveMem 9.0.0', 'tool_count', '34', 1.0, 'committed', 'seed')");

        Map<String, Object> res = svc.kgAlias(principal, "HiveMem",
                List.of("hivemem-mcp-server", "HiveMem 9.0.0"), false);

        assertThat(res).containsEntry("migrated", 2);
        assertThat(((Number) res.get("resulting_conflicts")).intValue()).isEqualTo(1);
    }

    @Test
    void aliasRegistersEntityForFutureWrites() {
        svc.kgAlias(principal, "HiveMem", List.of("hivemem-mcp-server"), false);
        svc.kgAdd(principal, "hivemem-mcp-server", "kind", "server", 1.0, null, null, null, "insert");
        long canonical = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE subject = ?", "HiveMem").get("n", Long.class);
        assertThat(canonical).isEqualTo(1);
    }

    @Test
    void backlogAcceptance_threeFragmentedToolCountFactsCollapseToOne() {
        // Three fragmented subjects, same predicate, different objects (the real prod situation).
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('hivemem-mcp-server','tool_count','49',1.0,'committed','s')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem 9.0.0','tool_count','34',1.0,'committed','s')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem MCP tool count','tool_count','45',1.0,'committed','s')");

        Map<String, Object> alias = svc.kgAlias(principal, "HiveMem",
                List.of("hivemem-mcp-server", "HiveMem 9.0.0", "HiveMem MCP tool count"), false);
        assertThat(alias).containsEntry("migrated", 3);

        Map<String, Object> add = svc.kgAdd(principal, "HiveMem", "tool_count", "52",
                1.0, null, null, null, "supersede");
        assertThat(((Number) add.get("superseded")).intValue()).isGreaterThanOrEqualTo(1);

        long active = dsl.fetchOne(
                "SELECT count(*) AS n FROM active_facts WHERE predicate = ?", "tool_count").get("n", Long.class);
        assertThat(active).isEqualTo(1);
        String obj = dsl.fetchOne(
                "SELECT \"object\" AS o FROM active_facts WHERE predicate = ?", "tool_count").get("o", String.class);
        assertThat(obj).isEqualTo("52");
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
