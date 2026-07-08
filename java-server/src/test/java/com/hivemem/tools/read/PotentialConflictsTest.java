package com.hivemem.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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

import com.hivemem.search.DataQualityRepository;

@Testcontainers
class PotentialConflictsTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private DataQualityRepository repo;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM facts");
        repo = new DataQualityRepository(dsl);
    }

    @Test
    void flagsPredicateWithMultipleDistinctSubjects() {
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('hivemem-mcp-server','tool_count','49',1.0,'committed','s')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem 9.0.0','tool_count','34',1.0,'committed','s')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem MCP tool count','tool_count','45',1.0,'committed','s')");

        List<Map<String, Object>> conflicts = repo.potentialConflicts(0.3, 50);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).containsEntry("predicate", "tool_count");
        @SuppressWarnings("unchecked")
        List<String> subjects = (List<String>) conflicts.get(0).get("subjects");
        assertThat(subjects).containsExactlyInAnyOrder(
                "hivemem-mcp-server", "HiveMem 9.0.0", "HiveMem MCP tool count");
    }

    @Test
    void singleSubjectPredicateIsNotFlagged() {
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem','version','9.17',1.0,'committed','s')");
        assertThat(repo.potentialConflicts(0.3, 50)).isEmpty();
    }

    @Test
    void ranksSimilarPairsAboveThreshold() {
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem 9.0.0','kind','a',1.0,'committed','s')");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem 9.0.1','kind','b',1.0,'committed','s')");

        List<Map<String, Object>> conflicts = repo.potentialConflicts(0.3, 50);
        assertThat(conflicts).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) conflicts.get(0).get("similar_pairs");
        assertThat(pairs).isNotEmpty();
    }

    @Test
    void skipsPairwiseSimilarityWhenSubjectsExceedCap() {
        for (int i = 0; i <= 50; i++) {
            String subject = String.format("subj-%02d", i);
            dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES (?,'many',?,1.0,'committed','s')",
                    subject, "obj-" + i);
        }

        List<Map<String, Object>> conflicts = repo.potentialConflicts(0.3, 50);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).containsEntry("subject_count", 51);
        assertThat(conflicts.get(0)).containsEntry("subjects_truncated", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) conflicts.get(0).get("similar_pairs");
        assertThat(pairs).isEmpty();
        @SuppressWarnings("unchecked")
        List<String> subjects = (List<String>) conflicts.get(0).get("subjects");
        assertThat(subjects).hasSize(50);
    }
}
