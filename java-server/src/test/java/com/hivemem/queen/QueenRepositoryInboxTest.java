package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Mirrors the Testcontainers Postgres integration setup used by QueenRepositoryIT
// (and the other *IntegrationTest/*IT classes in this codebase): an autowired
// DSLContext `db` against a Flyway-migrated schema. There is no shared abstract
// base class in this project -- each IT test duplicates this boilerplate.
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(QueenRepositoryInboxTest.TestConfig.class)
class QueenRepositoryInboxTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired DSLContext db;

    // No shared-container cleanup between tests otherwise (matches the pattern used
    // by e.g. SummarizeBudgetTrackerIT) -- without it, cells inserted by one test
    // method leak into the next and break the exact-match assertions below.
    @BeforeEach
    void cleanInboxAndWorkCells() {
        db.execute("DELETE FROM cells WHERE realm IN ('inbox', 'work')");
    }

    private UUID insertCell(String realm, String... tags) {
        UUID id = UUID.randomUUID();
        String arr = tags.length == 0 ? "'{}'" :
                "ARRAY[" + String.join(",", Arrays.stream(tags).map(t -> "'" + t + "'").toList()) + "]::text[]";
        db.execute("INSERT INTO cells (id, content, realm, signal, topic, status, tags, created_at) "
                + "VALUES (?, 'x', ?, 'facts', 't', 'committed', " + arr + ", now())", id, realm);
        return id;
    }

    @Test
    void selectsOnlyReadyInboxCells() {
        QueenRepository repo = new QueenRepository(db);
        UUID ready = insertCell("inbox");                              // classifiable
        UUID okPermFail = insertCell("inbox", "ocr_pending", "ocr_failed_permanent"); // terminal OCR fail -> included
        UUID onlyEmbedding = insertCell("inbox", "embedding_pending"); // embedding not blocking -> included
        insertCell("inbox", "needs_summary");                         // summary pending -> excluded
        insertCell("inbox", "ocr_pending");                           // OCR still running -> excluded
        insertCell("inbox", "vision_pending");                        // vision running -> excluded
        insertCell("inbox", "ocr_failed_permanent", "vision_pending"); // OCR terminal BUT vision still running -> excluded (OR-scoping)
        insertCell("inbox", "archivist_skipped");                     // already declined -> excluded
        insertCell("work");                                           // wrong realm -> excluded

        List<UUID> ids = repo.findInboxCellIds(50);

        assertThat(ids).containsExactlyInAnyOrder(ready, okPermFail, onlyEmbedding);
    }

    @Test
    void ordersFifoAndRespectsLimit() {
        QueenRepository repo = new QueenRepository(db);
        // 25 declined (poison) cells are oldest; 1 fresh classifiable cell is newest.
        for (int i = 0; i < 25; i++) insertCell("inbox", "archivist_skipped");
        UUID fresh = insertCell("inbox");
        List<UUID> ids = repo.findInboxCellIds(20);
        assertThat(ids).contains(fresh);           // not starved behind poison cells
        assertThat(ids).hasSizeLessThanOrEqualTo(20);
    }
}
