package com.hivemem.sync;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers task B5: reclassify persists old classification values + a reason in the op-log payload.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ReclassifyReasonTest.TestConfig.class)
class ReclassifyReasonTest {

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

    private String latestPayload(String opType) {
        return dsl.fetchOne("SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = ? ORDER BY seq DESC LIMIT 1", opType)
                .get("p", String.class);
    }

    @Test
    void persistsOldNewAndReasonInOpLog() {
        Map<String, Object> created = service.addCell(
                new AuthPrincipal("admin", AuthRole.ADMIN), "x", "inbox", "facts", "unsorted",
                null, List.of(), 1, "s", List.of(), null, null, null, null, null);
        UUID id = UUID.fromString((String) created.get("id"));
        AuthPrincipal archivist = new AuthPrincipal("inbox-archivist", AuthRole.AGENT);

        service.reclassifyCell(archivist, id, "work", "steuer", "events", "Finanzamt-Rechnung");

        String payload = latestPayload("reclassify_cell");
        assertThat(payload)
                .contains("\"old_realm\": \"inbox\"").contains("\"old_topic\": \"unsorted\"")
                .contains("\"old_signal\": \"facts\"")
                .contains("\"new_realm\": \"work\"").contains("\"new_signal\": \"events\"")
                .contains("\"reason\": \"Finanzamt-Rechnung\"")
                .contains("\"agent_id\": \"inbox-archivist\"");
    }

    @Test
    void reasonNullRemainsBackwardCompatible() {
        Map<String, Object> created = service.addCell(
                new AuthPrincipal("admin", AuthRole.ADMIN), "x", "inbox", "facts", "unsorted",
                null, List.of(), 1, "s", List.of(), null, null, null, null, null);
        UUID id = UUID.fromString((String) created.get("id"));
        AuthPrincipal queen = new AuthPrincipal("queen", AuthRole.AGENT);

        // old_* still captured even when reason is null
        service.reclassifyCell(queen, id, "work", null, null, null);

        String payload = latestPayload("reclassify_cell");
        assertThat(payload).contains("\"old_realm\": \"inbox\"").contains("\"reason\": null");
    }
}
