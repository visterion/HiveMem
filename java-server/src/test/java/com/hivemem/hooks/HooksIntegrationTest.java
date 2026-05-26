package com.hivemem.hooks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the hook endpoint.
 *
 * <p>Boots the full Spring Boot context against a real Postgres testcontainer and
 * exercises the chain HTTP -> AuthFilter -> HooksController -> HookContextService ->
 * EmbeddingClient -> CellSearchRepository -> ranked_search SQL -> ContextFormatter.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(HooksIntegrationTest.TestConfig.class)
@TestPropertySource(properties = "hivemem.hooks.relevance-threshold=0.0")
@Testcontainers
class HooksIntegrationTest {

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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WriteToolService writeToolService;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void hookEndpointInjectsHivememContextWithSeededCellId() throws Exception {
        // Seed a cell whose content/summary contain the deterministic "semantic"
        // marker recognised by FixedEmbeddingClient -> [1,0,0]. The query prompt
        // also contains "semantic" so embeddings collide and similarity is 1.0.
        Map<String, Object> seeded = writeToolService.addCell(
                new AuthPrincipal("integration-writer", AuthRole.WRITER),
                "semantic plan: project X phase 3 SDK wrapper rollout in 4 weeks",
                "eng",
                "facts",
                "planning",
                "system",
                java.util.List.of(),
                1,
                "Phase 3 plan for project X: SDK wrapper, 4 weeks (semantic)",
                java.util.List.of(),
                null,
                null,
                "committed",
                null,
                null
        );
        UUID seededId = UUID.fromString((String) seeded.get("id"));

        String body = """
                {"hook_event_name":"UserPromptSubmit",
                 "prompt":"semantic: what was the plan for project X phase 3?",
                 "session_id":"it-1"}
                """;

        ResponseEntity<String> response = post("/hooks/context", body, "good-token");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("hookSpecificOutput").path("hookEventName").asText())
                .isEqualTo("UserPromptSubmit");
        String additional = root.path("hookSpecificOutput").path("additionalContext").asText();
        assertThat(additional).contains("hivemem_context");
        assertThat(additional).contains(seededId.toString().substring(0, 8));
    }

    @Test
    void trivialPromptReturnsEmptyAdditionalContext() throws Exception {
        String body = """
                {"hook_event_name":"UserPromptSubmit",
                 "prompt":"ok",
                 "session_id":"it-2"}
                """;

        ResponseEntity<String> response = post("/hooks/context", body, "good-token");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("hookSpecificOutput").path("hookEventName").asText())
                .isEqualTo("UserPromptSubmit");
        assertThat(root.path("hookSpecificOutput").path("additionalContext").asText())
                .isEmpty();
    }

    @Test
    void citedSourcesContainLinkedReferenceWhenCellIsInjected() throws Exception {
        Map<String, Object> seeded = writeToolService.addCell(
                new AuthPrincipal("integration-writer", AuthRole.WRITER),
                "semantic plan: project X phase 3 SDK wrapper rollout in 4 weeks",
                "eng",
                "facts",
                "planning",
                "system",
                java.util.List.of(),
                1,
                "Phase 3 plan for project X: SDK wrapper, 4 weeks (semantic)",
                java.util.List.of(),
                null,
                null,
                "committed",
                null,
                null
        );
        UUID seededId = UUID.fromString((String) seeded.get("id"));

        Map<String, Object> ref = writeToolService.addReference(
                "SDK Design Doc", "https://docs.example.com/sdk", null, "article", "read",
                null, java.util.List.of(), null);
        UUID refId = UUID.fromString((String) ref.get("id"));
        writeToolService.linkReference(seededId, refId, "source");

        String body = """
                {"hook_event_name":"UserPromptSubmit",
                 "prompt":"semantic: what was the plan for project X phase 3?",
                 "session_id":"it-3"}
                """;

        ResponseEntity<String> response = post("/hooks/context", body, "good-token");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode sources = root.path("citedSources");
        assertThat(sources.isArray()).isTrue();
        assertThat(sources.size()).isEqualTo(1);

        JsonNode first = sources.get(0);
        assertThat(first.path("referenceTitle").asText()).isEqualTo("SDK Design Doc");
        assertThat(first.path("referenceUrl").asText()).isEqualTo("https://docs.example.com/sdk");
        assertThat(first.path("cellId").asText()).isEqualTo(seededId.toString());
        assertThat(first.path("realm").asText()).isEqualTo("eng");
        assertThat(first.path("topic").asText()).isEqualTo("planning");
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }

        @Bean
        @org.springframework.context.annotation.Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
