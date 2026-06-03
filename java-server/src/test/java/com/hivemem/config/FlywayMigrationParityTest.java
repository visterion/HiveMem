package com.hivemem.config;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayMigrationParityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Test
    void migrationsRunFromEmptyDatabase() throws SQLException {
        try (SchemaHarness harness = migrateFreshSchema()) {
            assertThat(harness.flyway().info().pending()).isEmpty();
            assertThat(harness.dsl().fetchCount(DSL.table("migration_baseline"))).isEqualTo(1);
            assertThat(harness.dsl().fetchCount(DSL.table("flyway_schema_history"))).isEqualTo(28);
        }
    }

    @Test
    void migrationsAreIdempotentOnSecondRun() throws SQLException {
        try (SchemaHarness harness = migrateFreshSchema()) {
            assertThat(harness.flyway().migrate().migrationsExecuted).isZero();
            assertThat(harness.dsl().fetchCount(DSL.table("flyway_schema_history"))).isEqualTo(28);
        }
    }

    @Test
    void tunnelsSchemaHasExpectedColumnsAndConstraints() throws SQLException {
        try (SchemaHarness harness = migrateFreshSchema()) {
            List<String> columns = harness.dsl().fetch("""
                    select column_name
                    from information_schema.columns
                    where table_schema = ?
                      and table_name = 'tunnels'
                    order by ordinal_position
                    """, harness.schema()).getValues(0, String.class);

            assertThat(columns).contains(
                    "from_cell",
                    "to_cell",
                    "relation",
                    "status",
                    "valid_until"
            );

            String cellA = insertCell(harness.dsl(), "Cell A");
            String cellB = insertCell(harness.dsl(), "Cell B");

            assertThatThrownBy(() -> harness.dsl().execute("""
                            insert into tunnels (from_cell, to_cell, relation, created_by)
                            values (?::uuid, ?::uuid, 'related_to', 'test')
                            """,
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000002"))
                    .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> harness.dsl().execute("""
                            insert into tunnels (from_cell, to_cell, relation, created_by)
                            values (?::uuid, ?::uuid, 'invalid', 'test')
                            """,
                    cellA,
                    cellB))
                    .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> harness.dsl().execute("""
                            insert into tunnels (from_cell, to_cell, relation, status, created_by)
                            values (?::uuid, ?::uuid, 'related_to', 'invalid', 'test')
                            """,
                    cellA,
                    cellB))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void activeViewsExist() throws SQLException {
        try (SchemaHarness harness = migrateFreshSchema()) {
            List<String> viewNames = harness.dsl().fetch("""
                    select table_name
                    from information_schema.views
                    where table_schema = ?
                      and table_name like 'active_%'
                    order by table_name
                    """, harness.schema()).getValues(0, String.class);

            assertThat(viewNames).contains(
                    "active_blueprints",
                    "active_cells",
                    "active_facts",
                    "active_tunnels"
            );
            assertThat(harness.dsl().fetchCount(DSL.table("pending_approvals"))).isZero();
        }
    }

    private static SchemaHarness migrateFreshSchema() throws SQLException {
        String schema = "parity_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection adminConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            DSL.using(adminConnection, SQLDialect.POSTGRES)
                    .execute("create schema " + schema);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema, "public")
                .defaultSchema(schema)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(0);

        Connection schemaConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        DSLContext dsl = DSL.using(schemaConnection, SQLDialect.POSTGRES);
        dsl.execute("set search_path to " + schema + ", public");
        return new SchemaHarness(schema, flyway, schemaConnection, dsl);
    }

    private static String insertCell(DSLContext dsl, String content) {
        return dsl.fetchOne("""
                insert into cells (content, realm, created_by)
                values (?, 'test', 'test')
                returning id::text
                """, content).get(0, String.class);
    }

    private record SchemaHarness(String schema, Flyway flyway, Connection connection, DSLContext dsl) implements AutoCloseable {

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }
}
