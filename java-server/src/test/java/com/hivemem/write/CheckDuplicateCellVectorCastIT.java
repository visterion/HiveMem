package com.hivemem.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * V0046 redefines check_duplicate_cell to cast embedding to vector(dim) — dim derived at call
 * time from vector_dims(query_embedding), never hardcoded — so the HNSW index
 * (idx_cells_embedding, an expression index on embedding::vector(dim)) is actually used instead
 * of bypassed via a bare `embedding <=> query_embedding` comparison. This IT confirms the
 * migration preserves behavior: near-duplicates within threshold are still found, and dissimilar
 * cells are not.
 */
@Testcontainers
class CheckDuplicateCellVectorCastIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private WriteToolRepository repo;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cells");

        repo = new WriteToolRepository(dsl);
    }

    @Test
    void findsCellWithinThresholdAndIgnoresDissimilar() {
        UUID close = insertCell("Rechnung 4711", unitVector(0));
        insertCell("Mietvertrag", unitVector(1)); // orthogonal — cosine similarity 0, well below threshold

        List<Map<String, Object>> results = repo.checkDuplicateCell(unitVector(0), 0.95);

        assertThat(results).extracting(r -> r.get("id")).contains(close.toString());
        assertThat(results).hasSize(1);
    }

    @Test
    void findsNothingWhenNoCellIsWithinThreshold() {
        insertCell("Mietvertrag", unitVector(1));

        List<Map<String, Object>> results = repo.checkDuplicateCell(unitVector(0), 0.95);

        assertThat(results).isEmpty();
    }

    private UUID insertCell(String content, String vectorLiteral) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, 'committed', ?::timestamptz, now())",
                id, content, vectorLiteral, OffsetDateTime.now());
        return id;
    }

    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }
}
