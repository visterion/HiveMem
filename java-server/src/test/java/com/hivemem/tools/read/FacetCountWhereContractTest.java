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
@Import(FacetCountWhereContractTest.TestConfig.class)
@Testcontainers
class FacetCountWhereContractTest {

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
    void whereRealmInFiltersToMatchingRealmsIncludingNoneSentinel() throws Exception {
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000002001"), "a", "facts", "t");
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000002002"), "b", "facts", "t");
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000002003"), null, "facts", "t");
        insertCell(UUID.fromString("00000000-0000-0000-0000-000000002004"), "other", "facts", "t");

        JsonNode result = callTool("writer-token", "facet_count", Map.of(
                "where", Map.of("realm_in", List.of("a", "b", "none")),
                "fields", List.of("status")
        ));

        int count = result.path("status").get(0).path("count").asInt();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void whereRealmInWithFactFacetExercisesAliasRewrite() throws Exception {
        UUID docA = UUID.fromString("00000000-0000-0000-0000-000000002011");
        UUID docB = UUID.fromString("00000000-0000-0000-0000-000000002012");
        UUID docOther = UUID.fromString("00000000-0000-0000-0000-000000002013");
        insertCell(docA, "a", "facts", "fact_t");
        insertCell(docB, "b", "facts", "fact_t");
        insertCell(docOther, "other", "facts", "fact_t");
        insertFact(docA, "vendor", "AcmeCorp");
        insertFact(docB, "vendor", "AcmeCorp");
        insertFact(docOther, "vendor", "OtherCorp");

        JsonNode result = callTool("writer-token", "facet_count", Map.of(
                "where", Map.of("realm_in", List.of("a", "b")),
                "fields", List.of("fact:vendor")
        ));

        JsonNode vendorFacet = result.path("fact:vendor");
        assertThat(vendorFacet).hasSize(1);
        assertThat(vendorFacet.get(0).path("value").asText()).isEqualTo("AcmeCorp");
        assertThat(vendorFacet.get(0).path("count").asInt()).isEqualTo(2);
    }

    @Test
    void whereQueryWorksAsTextFilter() throws Exception {
        insertCellWithContent(UUID.fromString("00000000-0000-0000-0000-000000002021"), "topic drawer alpha", "a", "facts", "t");
        insertCellWithContent(UUID.fromString("00000000-0000-0000-0000-000000002022"), "unrelated content", "a", "facts", "t");

        JsonNode result = callTool("writer-token", "facet_count", Map.of(
                "where", Map.of("query", "drawer"),
                "fields", List.of("status")
        ));

        int count = result.path("status").get(0).path("count").asInt();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void whereWithFlatStatusIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "facet_count",
                                        "arguments", Map.of(
                                                "status", "committed",
                                                "where", Map.of("signal", "facts"),
                                                "fields", List.of("status")
                                        )
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("where")));
    }

    @Test
    void whereWithFlatQueryIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "facet_count",
                                        "arguments", Map.of(
                                                "query", "drawer",
                                                "where", Map.of("signal", "facts"),
                                                "fields", List.of("status")
                                        )
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("where")));
    }

    @Test
    void whereDefaultsStatusToCommittedUnlikeFlatParams() throws Exception {
        insertCellWithStatus(UUID.fromString("00000000-0000-0000-0000-000000002031"), "doc content", "statusdefault", "facts", "t", "pending");
        insertCellWithStatus(UUID.fromString("00000000-0000-0000-0000-000000002032"), "doc content", "statusdefault", "facts", "t", "committed");

        JsonNode flatResult = callTool("writer-token", "facet_count", Map.of(
                "realm", "statusdefault",
                "fields", List.of("status")
        ));
        int flatCount = 0;
        for (JsonNode entry : flatResult.path("status")) {
            flatCount += entry.path("count").asInt();
        }
        assertThat(flatCount).isEqualTo(2);

        JsonNode whereResult = callTool("writer-token", "facet_count", Map.of(
                "where", Map.of("realm", "statusdefault"),
                "fields", List.of("status")
        ));
        int whereCount = 0;
        for (JsonNode entry : whereResult.path("status")) {
            whereCount += entry.path("count").asInt();
        }
        assertThat(whereCount).isEqualTo(1);
        assertThat(whereResult.path("status").get(0).path("value").asText()).isEqualTo("committed");

        JsonNode wherePendingResult = callTool("writer-token", "facet_count", Map.of(
                "where", Map.of("realm", "statusdefault", "status", "pending"),
                "fields", List.of("status")
        ));
        int wherePendingCount = 0;
        for (JsonNode entry : wherePendingResult.path("status")) {
            wherePendingCount += entry.path("count").asInt();
        }
        assertThat(wherePendingCount).isEqualTo(1);
        assertThat(wherePendingResult.path("status").get(0).path("value").asText()).isEqualTo("pending");
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

    private void insertCell(UUID id, String realm, String signal, String topic) {
        insertCellWithContent(id, "doc content", realm, signal, topic);
    }

    private void insertCellWithContent(UUID id, String content, String realm, String signal, String topic) {
        insertCellWithStatus(id, content, realm, signal, topic, "committed");
    }

    private void insertCellWithStatus(UUID id, String content, String realm, String signal, String topic, String status) {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-04-03T10:00:00Z");
        dslContext.execute(
                """
                INSERT INTO cells (
                    id, content, realm, signal, topic, importance, summary, status, created_by, created_at, valid_from
                ) VALUES (?, ?, ?, ?, ?, 3, ?, ?, 'writer-1', ?::timestamptz, ?::timestamptz)
                """,
                id, content, realm, signal, topic, content, status, createdAt, createdAt
        );
    }

    private void insertFact(UUID sourceId, String predicate, String object) {
        dslContext.execute(
                "INSERT INTO facts (subject, predicate, \"object\", confidence, source_id, status, valid_from) " +
                "VALUES ('doc', ?, ?, 1.0, ?, 'committed', now())",
                predicate, object, sourceId);
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
