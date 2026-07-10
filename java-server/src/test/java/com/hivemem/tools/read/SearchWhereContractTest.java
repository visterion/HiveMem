package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SearchWhereContractTest.TestConfig.class)
@Testcontainers
class SearchWhereContractTest {

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

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void whereRealmInFiltersToMatchingRealms() throws Exception {
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000001001"), "topic drawer alpha", "a", "facts", "t", OffsetDateTime.parse("2026-04-03T10:00:00Z"));
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000001002"), "topic drawer bravo", "b", "facts", "t", OffsetDateTime.parse("2026-04-03T10:00:00Z"));
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000001003"), "topic drawer charlie", "other", "facts", "t", OffsetDateTime.parse("2026-04-03T10:00:00Z"));

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "topic drawer",
                "where", Map.of("realm_in", List.of("a", "b"))
        ));

        assertThat(textValues(results, "realm")).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void whereRealmNoneMatchesFlatRealmNoneParity() throws Exception {
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000001011"), "orphan topic drawer", null, "facts", "t", OffsetDateTime.parse("2026-04-03T10:00:00Z"));
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000001012"), "assigned topic drawer", "eng", "facts", "t", OffsetDateTime.parse("2026-04-03T10:00:00Z"));

        JsonNode flatResults = callTool("writer-token", "search", Map.of(
                "query", "topic drawer",
                "realm", "none"
        ));
        JsonNode whereResults = callTool("writer-token", "search", Map.of(
                "query", "topic drawer",
                "where", Map.of("realm", "none")
        ));

        assertThat(textValues(whereResults, "id")).containsExactlyInAnyOrderElementsOf(textValues(flatResults, "id"));
        assertThat(textValues(whereResults, "id")).containsExactly("00000000-0000-0000-0000-000000001011");
    }

    @Test
    void whereWithFlatRealmIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "search",
                                        "arguments", Map.of(
                                                "query", "topic drawer",
                                                "realm", "eng",
                                                "where", Map.of("signal", "facts")
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("where")));
    }

    @Test
    void whereQueryIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "search",
                                        "arguments", Map.of(
                                                "query", "topic drawer",
                                                "where", Map.of("query", "x")
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
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
        String textContent = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
    }

    private List<String> textValues(JsonNode results, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : results) {
            values.add(row.path(field).asText());
        }
        return values;
    }

    private void insertCell(UUID id, String content, String realm, String signal, String topic, OffsetDateTime createdAt) {
        dslContext.execute(
                """
                INSERT INTO cells (
                    id, content, realm, signal, topic, importance, summary, status, created_by, created_at, valid_from
                ) VALUES (?, ?, ?, ?, ?, 3, ?, 'committed', 'writer-1', ?::timestamptz, ?::timestamptz)
                """,
                id, content, realm, signal, topic, content, createdAt, createdAt
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
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
