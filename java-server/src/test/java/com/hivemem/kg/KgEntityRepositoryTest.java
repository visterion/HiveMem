package com.hivemem.kg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class KgEntityRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private KgEntityRepository repo;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM kg_entity");
        repo = new KgEntityRepository(dsl);
    }

    @Test
    void unknownSubjectPassesThroughTrimmed() {
        assertThat(repo.resolve("  Unknown Thing ")).isEqualTo("Unknown Thing");
    }

    @Test
    void aliasResolvesToCanonicalCaseInsensitively() {
        repo.upsert("HiveMem", List.of("hivemem-mcp-server", "HiveMem 9.0.0"), "tester");
        assertThat(repo.resolve("HIVEMEM 9.0.0")).isEqualTo("HiveMem");
        assertThat(repo.resolve("hivemem-mcp-server")).isEqualTo("HiveMem");
    }

    @Test
    void canonicalNameResolvesToItself() {
        repo.upsert("HiveMem", List.of("hivemem-mcp-server"), "tester");
        assertThat(repo.resolve("hivemem")).isEqualTo("HiveMem");
    }

    @Test
    void canonicalSelfMatchViaStoredNormalizedCanonical() {
        repo.upsert("HiveMem", List.of("alias-a"), "tester");
        assertThat(repo.resolve("HIVEMEM")).isEqualTo("HiveMem");
    }

    @Test
    void casingVariantCanonicalMergesIntoExistingRow() {
        repo.upsert("HiveMem", List.of("alias-a"), "tester");
        repo.upsert("hivemem", List.of("alias-b"), "tester");
        // The first spelling wins; the variant's aliases are unioned in.
        assertThat(repo.resolve("alias-a")).isEqualTo("HiveMem");
        assertThat(repo.resolve("alias-b")).isEqualTo("HiveMem");
        long rows = dsl.fetchOne("SELECT count(*) AS n FROM kg_entity").get("n", Long.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void upsertUnionsAliasesOnConflict() {
        repo.upsert("HiveMem", List.of("alias-a"), "tester");
        repo.upsert("HiveMem", List.of("alias-b"), "tester");
        assertThat(repo.resolve("alias-a")).isEqualTo("HiveMem");
        assertThat(repo.resolve("alias-b")).isEqualTo("HiveMem");
        long rows = dsl.fetchOne("SELECT count(*) AS n FROM kg_entity").get("n", Long.class);
        assertThat(rows).isEqualTo(1);
    }
}
