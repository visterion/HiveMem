package com.hivemem.tools.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.AdminToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CrossFeatureParityIntegrationTest.TestConfig.class)
@Testcontainers
class CrossFeatureParityIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private AdminToolService adminToolService;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    @Test
    void reviseDrawerPreservesProgressiveLayers() throws Exception {
        JsonNode drawer = callTool("writer-token", "add_cell", Map.of(
                "content", "Original content about auth migration",
                "realm", "eng",
                "signal", "facts",
                "topic", "auth",
                "summary", "Auth migration v1",
                "key_points", List.of("Migrate from Camunda", "Use Temporal", "Q3 deadline"),
                "insight", "This unblocks the Go rewrite",
                "actionability", "actionable",
                "importance", 1
        ));
        String drawerId = drawer.path("id").asText();

        JsonNode revision = callTool("writer-token", "revise_cell", Map.of(
                "old_id", drawerId,
                "new_content", "Updated content about auth migration complete"
        ));
        JsonNode revisedDrawer = callTool("writer-token", "get_cell", Map.of(
                "cell_id", revision.path("new_id").asText()
        ));

        assertThat(revisedDrawer.path("summary").asText()).isEqualTo("Auth migration v1");
        assertThat(textValues(revisedDrawer.path("key_points")))
                .containsExactly("Migrate from Camunda", "Use Temporal", "Q3 deadline");
        assertThat(revisedDrawer.path("insight").asText()).isEqualTo("This unblocks the Go rewrite");
        assertThat(revisedDrawer.path("actionability").asText()).isEqualTo("actionable");
        assertThat(revisedDrawer.path("importance").asInt()).isEqualTo(1);
    }

    @Test
    void reviseFactPreservesSourceId() throws Exception {
        JsonNode drawer = callTool("writer-token", "add_cell", Map.of(
                "content", "Source drawer for fact",
                "realm", "eng",
                "signal", "facts",
                "topic", "test"
        ));

        JsonNode fact = callTool("writer-token", "kg_add", Map.of(
                "subject", "HiveMem",
                "predicate", "uses",
                "object_", "PostgreSQL",
                "source_id", drawer.path("id").asText()
        ));

        JsonNode revision = callTool("writer-token", "revise_fact", Map.of(
                "old_id", fact.path("id").asText(),
                "new_object", "PostgreSQL 17"
        ));

        UUID revisedFactId = UUID.fromString(revision.path("new_id").asText());
        UUID sourceId = dslContext.fetchOne("SELECT source_id FROM facts WHERE id = ?", revisedFactId)
                .get("source_id", UUID.class);
        assertThat(sourceId).isEqualTo(UUID.fromString(drawer.path("id").asText()));
    }

    @Test
    void popularityStaysZeroBeforeRefresh() throws Exception {
        JsonNode drawer = callTool("writer-token", "add_cell", Map.of(
                "content", "Content about Docker container orchestration",
                "realm", "eng",
                "signal", "facts",
                "topic", "infra",
                "summary", "Docker orchestration"
        ));

        UUID drawerUuid = UUID.fromString(drawer.path("id").asText());
        for (int i = 0; i < 5; i++) {
            adminToolService.logAccess(drawerUuid, null, "admin");
        }

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "Docker container orchestration",
                "weight_semantic", 0.0d,
                "weight_keyword", 0.0d,
                "weight_recency", 0.0d,
                "weight_importance", 0.0d,
                "weight_popularity", 1.0d,
                "include", List.of("scores")
        ));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("score_popularity").asDouble()).isEqualTo(0.0d);
        assertThat(results.get(0).path("score_total").asDouble()).isEqualTo(0.0d);
    }

    @Test
    void blueprintKeyDrawersRemainResolvableAfterDrawerRevision() throws Exception {
        JsonNode drawer = callTool("writer-token", "add_cell", Map.of(
                "content", "Important drawer",
                "realm", "eng",
                "signal", "facts",
                "topic", "arch",
                "summary", "Key architecture decision"
        ));
        String originalDrawerId = drawer.path("id").asText();

        callTool("writer-token", "update_blueprint", Map.of(
                "realm", "eng",
                "title", "Engineering Overview",
                "narrative", "Architecture decisions",
                "key_cells", List.of(originalDrawerId)
        ));

        JsonNode revision = callTool("writer-token", "revise_cell", Map.of(
                "old_id", originalDrawerId,
                "new_content", "Updated important drawer"
        ));
        String revisedDrawerId = revision.path("new_id").asText();

        JsonNode blueprints = callTool("writer-token", "get_blueprint", Map.of(
                "realm", "eng"
        ));
        assertThat(textValues(blueprints.get(0).path("key_cells"))).contains(originalDrawerId);
        assertThat(textValues(blueprints.get(0).path("key_cells"))).doesNotContain(revisedDrawerId);

        JsonNode originalDrawer = callTool("writer-token", "get_cell", Map.of(
                "cell_id", originalDrawerId
        ));
        assertThat(originalDrawer.path("id").asText()).isEqualTo(originalDrawerId);
        assertThat(originalDrawer.path("valid_until").isNull()).isFalse();
    }

    @Test
    void fullAgentPipelineWorksEndToEnd() throws Exception {
        callTool("writer-token", "register_agent", Map.of(
                "name", "agent-1",
                "focus", "Curate and organize knowledge"
        ));
        callTool("writer-token", "diary_write", Map.of(
                "agent", "agent-1",
                "entry", "Found duplicate content in engineering wing"
        ));

        JsonNode diary = callTool("writer-token", "diary_read", Map.of(
                "agent", "agent-1"
        ));
        assertThat(diary).hasSize(1);
        assertThat(diary.get(0).path("entry").asText()).isEqualTo("Found duplicate content in engineering wing");

        JsonNode drawer = callTool("agent-token", "add_cell", Map.of(
                "content", "Curated summary of authentication patterns",
                "realm", "eng",
                "signal", "facts",
                "topic", "auth",
                "summary", "Auth patterns curated",
                "status", "committed"
        ));
        String drawerId = drawer.path("id").asText();
        assertThat(drawer.path("status").asText()).isEqualTo("pending");

        JsonNode pending = callTool("writer-token", "pending_approvals", Map.of());
        JsonNode pendingDrawer = findById(pending, drawerId);
        assertThat(pendingDrawer).isNotNull();
        assertThat(pendingDrawer.path("created_by").asText()).isEqualTo("agent-1");

        callTool("admin-token", "approve_pending", Map.of(
                "ids", List.of(drawerId),
                "decision", "committed"
        ));

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "authentication patterns"
        ));
        assertThat(textValues(results, "id")).contains(drawerId);
    }

    private JsonNode callTool(String token, String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", toolName,
                                        "arguments", arguments
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("error")).as("Unexpected error in response: %s", body).isFalse();
        String textContent = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
    }

    private static JsonNode findById(JsonNode results, String id) {
        for (JsonNode row : results) {
            if (id.equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private static List<String> textValues(JsonNode arrayNode) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode node : arrayNode) {
            values.add(node.asText());
        }
        return List.copyOf(values);
    }

    private static List<String> textValues(JsonNode results, String field) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode row : results) {
            values.add(row.path(field).asText());
        }
        return List.copyOf(values);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "agent-token" -> Optional.of(new AuthPrincipal("agent-1", AuthRole.AGENT));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin-1", AuthRole.ADMIN));
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
