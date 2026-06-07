package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.auth.TokenService;
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

import java.util.Arrays;
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
@Import(TagWriteTest.TestConfig.class)
@Testcontainers
class TagWriteTest {

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
    private RateLimiter rateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
    }

    // ---- Helpers ----

    private UUID seedCell(String content, String realm, String... initialTags) {
        String[] tagArray = initialTags == null ? new String[0] : initialTags;
        org.jooq.Record row = dslContext.fetchOne("""
                INSERT INTO cells (content, realm, signal, topic, tags, status, created_by, valid_from)
                VALUES (?, ?, ?, ?, ?, 'committed', 'test', now())
                RETURNING id
                """, content, realm, "facts", "test", tagArray);
        return row.get("id", UUID.class);
    }

    private String[] getCellTags(UUID id) {
        org.jooq.Record row = dslContext.fetchOne(
                "SELECT tags FROM cells WHERE id = ? AND valid_until IS NULL", id);
        if (row == null) {
            return new String[0];
        }
        String[] tags = row.get("tags", String[].class);
        return tags == null ? new String[0] : tags;
    }

    private JsonNode callToolContent(String token, String toolName, Map<String, Object> arguments) throws Exception {
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
        String textContent = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
    }

    // ---- Tests ----

    @Test
    void addTagsThenGetCellShowsTag() throws Exception {
        UUID cellId = seedCell("content for tagging", "test-realm");

        JsonNode result = callToolContent("writer-token", "add_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("invoice", "urgent")
        ));

        assertThat(result.path("updated").asInt()).isEqualTo(1);

        // Verify via get_cell
        JsonNode cell = callToolContent("writer-token", "get_cell", Map.of(
                "cell_id", cellId.toString()
        ));
        JsonNode tags = cell.path("tags");
        assertThat(tags.isArray()).isTrue();
        List<String> tagList = new java.util.ArrayList<>();
        tags.forEach(t -> tagList.add(t.asText()));
        assertThat(tagList).contains("invoice", "urgent");
    }

    @Test
    void addTagsIsIdempotentOnRepeat() throws Exception {
        UUID cellId = seedCell("idempotency test", "test-realm");

        // Add once
        callToolContent("writer-token", "add_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("invoice")
        ));

        // Add again (same tag)
        JsonNode result = callToolContent("writer-token", "add_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("invoice")
        ));
        assertThat(result.path("updated").asInt()).isEqualTo(1);

        // Only one occurrence in DB
        String[] tags = getCellTags(cellId);
        long invoiceCount = Arrays.stream(tags).filter("invoice"::equals).count();
        assertThat(invoiceCount).isEqualTo(1);
    }

    @Test
    void removeTagsRemovesTag() throws Exception {
        UUID cellId = seedCell("remove tag test", "test-realm", "invoice", "urgent", "draft");

        JsonNode result = callToolContent("writer-token", "remove_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("urgent")
        ));

        assertThat(result.path("updated").asInt()).isEqualTo(1);

        String[] tags = getCellTags(cellId);
        assertThat(tags).contains("invoice", "draft");
        assertThat(tags).doesNotContain("urgent");
    }

    @Test
    void removeTagsIsIdempotentForAbsentTag() throws Exception {
        UUID cellId = seedCell("remove idempotent test", "test-realm", "invoice");

        // Remove a tag that doesn't exist — should not error
        JsonNode result = callToolContent("writer-token", "remove_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("nonexistent-tag")
        ));

        assertThat(result.path("updated").asInt()).isEqualTo(1);
        String[] tags = getCellTags(cellId);
        assertThat(tags).contains("invoice");
    }

    @Test
    void writerAddTagsCommittedImmediately() throws Exception {
        UUID cellId = seedCell("writer commit test", "test-realm");

        callToolContent("writer-token", "add_tags", Map.of(
                "cell_id", cellId.toString(),
                "tags", List.of("committed-tag")
        ));

        // The cell's status should still be 'committed' (add_tags is in-place, no status change)
        org.jooq.Record row = dslContext.fetchOne(
                "SELECT status FROM cells WHERE id = ? AND valid_until IS NULL", cellId);
        assertThat(row).isNotNull();
        assertThat(row.get("status", String.class)).isEqualTo("committed");

        // The tag is visible (not pending)
        String[] tags = getCellTags(cellId);
        assertThat(tags).contains("committed-tag");
    }

    @Test
    void bulkTagOverTwoCells() throws Exception {
        UUID cell1 = seedCell("bulk cell 1", "test-realm");
        UUID cell2 = seedCell("bulk cell 2", "test-realm", "existing-tag");

        JsonNode result = callToolContent("writer-token", "bulk_tag", Map.of(
                "cell_ids", List.of(cell1.toString(), cell2.toString()),
                "add_tags", List.of("bulk-tag"),
                "remove_tags", List.of("existing-tag")
        ));

        assertThat(result.path("updated").asInt()).isEqualTo(2);

        // cell1: has bulk-tag, no existing-tag (didn't have it)
        String[] tags1 = getCellTags(cell1);
        assertThat(tags1).contains("bulk-tag");

        // cell2: has bulk-tag, existing-tag removed
        String[] tags2 = getCellTags(cell2);
        assertThat(tags2).contains("bulk-tag");
        assertThat(tags2).doesNotContain("existing-tag");
    }

    @Test
    void bulkTagAddOnlyNoRemove() throws Exception {
        UUID cell1 = seedCell("bulk add only 1", "test-realm");
        UUID cell2 = seedCell("bulk add only 2", "test-realm");

        JsonNode result = callToolContent("writer-token", "bulk_tag", Map.of(
                "cell_ids", List.of(cell1.toString(), cell2.toString()),
                "add_tags", List.of("new-tag")
        ));

        assertThat(result.path("updated").asInt()).isEqualTo(2);

        assertThat(getCellTags(cell1)).contains("new-tag");
        assertThat(getCellTags(cell2)).contains("new-tag");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
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
