package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(VistierieIngestIT.TestConfig.class)
class VistierieIngestIT {

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
        registry.add("hivemem.queen.enabled", () -> "true");
        registry.add("hivemem.queen.completion-webhook-token", () -> "cwt");
        registry.add("hivemem.queen.webhook-token", () -> "wt");
    }

    @Autowired DSLContext db;
    @Autowired MockMvc mvc;

    private UUID insertCell(String topic) {
        return db.fetchOne("""
                INSERT INTO cells (realm, signal, topic, content, status, created_by)
                VALUES ('work', 'facts', ?, 'content for ' || ?, 'committed', 'test')
                RETURNING id
                """, topic, topic).get("id", UUID.class);
    }

    @Test
    void completionWebhookCreatesPendingTunnelAndDedups() throws Exception {
        UUID a = insertCell("ingest-a-" + UUID.randomUUID());
        UUID b = insertCell("ingest-b-" + UUID.randomUUID());

        String body = """
                {"run_id":"r1","status":"done","output":{"surveyed":1,"proposals":[
                  {"from_cell":"%s","to_cell":"%s","relation":"related_to","note":"linked"}
                ]}}""".formatted(a, b);

        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer cwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Result<Record> rows = db.fetch(
                "SELECT status, created_by FROM tunnels WHERE from_cell=? AND to_cell=? AND relation=? AND valid_until IS NULL",
                a, b, "related_to");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status", String.class)).isEqualTo("pending");
        assertThat(rows.get(0).get("created_by", String.class)).isEqualTo("queen");

        // Second POST — dedup: still exactly one tunnel
        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer cwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Result<Record> rowsAfterDedup = db.fetch(
                "SELECT status, created_by FROM tunnels WHERE from_cell=? AND to_cell=? AND relation=? AND valid_until IS NULL",
                a, b, "related_to");
        assertThat(rowsAfterDedup).hasSize(1);
    }

    @Test
    void completionRejectsBadToken() throws Exception {
        String body = """
                {"run_id":"r2","status":"done","output":{"proposals":[]}}""";

        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
