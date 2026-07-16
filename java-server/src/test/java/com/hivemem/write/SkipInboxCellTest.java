package com.hivemem.write;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers task 4: skipInboxCell tags the cell as archivist_skipped and writes a
 * reason + agent_id into the op-log.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SkipInboxCellTest.TestConfig.class)
class SkipInboxCellTest {

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

    @Autowired DSLContext dsl;
    @Autowired WriteToolService service;

    @Test
    void tagsCellAndWritesSkipOpLog() {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, realm, signal, topic, status, tags, created_at) "
                + "VALUES ('" + id + "', 'x', 'inbox', 'facts', 'unsorted', 'committed', '{}', now())");

        service.skipInboxCell(new AuthPrincipal("inbox-archivist", AuthRole.AGENT), id, "ambiguous scan");

        Boolean tagged = dsl.fetchOne(
                "SELECT 'archivist_skipped' = ANY(tags) AS t FROM cells WHERE id = '" + id + "'")
                .get("t", Boolean.class);
        assertThat(tagged).isTrue();

        String payload = dsl.fetchOne("SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = 'archivist_skip' ORDER BY seq DESC LIMIT 1")
                .get("p", String.class);
        assertThat(payload)
                .contains("\"cell_id\": \"" + id + "\"")
                .contains("\"reason\": \"ambiguous scan\"")
                .contains("\"agent_id\": \"inbox-archivist\"");
    }

    @Test
    void blankReasonIsRejected() {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, realm, signal, topic, status, tags, created_at) "
                + "VALUES ('" + id + "', 'x', 'inbox', 'facts', 'unsorted', 'committed', '{}', now())");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.skipInboxCell(new AuthPrincipal("inbox-archivist", AuthRole.AGENT), id, "  "));
    }
}
