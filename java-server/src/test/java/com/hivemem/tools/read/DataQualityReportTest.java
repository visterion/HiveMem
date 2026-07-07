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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DataQualityReportTest.TestConfig.class)
@Testcontainers
class DataQualityReportTest {

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

    private static final FixedEmbeddingClient EMBEDDING_CLIENT = new FixedEmbeddingClient();

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
    }

    private UUID seedCell(String content, String realm, String signal, String topic) {
        Float[] embedding = EMBEDDING_CLIENT.encodeDocument(content).toArray(Float[]::new);
        org.jooq.Record row = dslContext.fetchOne("""
                INSERT INTO cells (content, summary, realm, signal, topic, status, created_by, valid_from, embedding)
                VALUES (?, ?, ?, ?, ?, 'committed', 'test', now(), ?::vector)
                RETURNING id
                """, content, content, realm, signal, topic, embedding);
        return row.get("id", UUID.class);
    }

    private void addTunnel(UUID from, UUID to) {
        dslContext.execute("""
                INSERT INTO tunnels (from_cell, to_cell, relation, status, created_by, valid_from)
                VALUES (?, ?, 'related_to', 'committed', 'test', now())
                """, from, to);
    }

    private void addFact(UUID sourceId, String subject, String predicate, String object) {
        dslContext.execute("""
                INSERT INTO facts (subject, predicate, object, source_id, confidence, created_by, valid_from)
                VALUES (?, ?, ?, ?, 1.0, 'test', now())
                """, subject, predicate, object, sourceId);
    }

    private JsonNode callToolContent(String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
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
        return body.path("result");
    }

    private JsonNode contentJson(JsonNode result) {
        String textContent = result.path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
    }

    @Test
    void reportsUnclassifiedDisconnectedAndDuplicateSections() throws Exception {
        // missing realm/signal/topic cells
        UUID missingRealm = seedCell("no realm cell", null, "facts", "topic-a");
        UUID missingSignal = seedCell("no signal cell", "realmx", null, "topic-b");
        UUID missingTopic = seedCell("no topic cell", "realmx", "facts", null);

        // disconnected: no tunnels, no facts
        UUID disconnected = seedCell("orphan cell content", "realmx", "facts", "topic-c");

        // connected cell (has a fact) — should NOT show up as disconnected
        UUID connected = seedCell("connected cell content", "realmx", "facts", "topic-d");
        addFact(connected, "subj", "pred", "obj");

        // tunnel-connected cells
        UUID tunnelA = seedCell("tunnel a content", "realmx", "facts", "topic-e");
        UUID tunnelB = seedCell("tunnel b content", "realmx", "facts", "topic-f");
        addTunnel(tunnelA, tunnelB);

        // identical content => identical embedding => similarity 1.0 duplicate pair
        UUID dupA = seedCell("duplicate pair content", "realmx", "facts", "topic-g");
        UUID dupB = seedCell("duplicate pair content", "realmx", "facts", "topic-h");

        // distinct cell, should not pair with anything at high threshold
        seedCell("totally unrelated distinct content zzz", "realmx", "facts", "topic-i");

        // FixedEmbeddingClient hashes distinct strings into vectors confined to the positive
        // octant, so unrelated pairs can still land above 0.90 cosine similarity. Use a
        // near-1.0 threshold here so only the truly identical pair (similarity == 1.0) survives;
        // the "still finds the identical pair at 0.99" behavior is covered by a dedicated test.
        JsonNode content = contentJson(callToolContent("data_quality_report", Map.of("threshold", 0.999)));

        assertThat(content.path("unclassified").path("missing_realm").path("count").asInt()).isEqualTo(1);
        assertThat(content.path("unclassified").path("missing_signal").path("count").asInt()).isEqualTo(1);
        assertThat(content.path("unclassified").path("missing_topic").path("count").asInt()).isEqualTo(1);

        JsonNode missingRealmSample = content.path("unclassified").path("missing_realm").path("sample").get(0);
        assertThat(missingRealmSample.path("id").asText()).isEqualTo(missingRealm.toString());
        assertThat(missingRealmSample.has("summary")).isTrue();

        // disconnected: missingRealm/missingSignal/missingTopic/disconnected all lack tunnels+facts too
        assertThat(content.path("disconnected").path("count").asInt()).isGreaterThanOrEqualTo(1);
        boolean disconnectedContainsOrphan = false;
        for (JsonNode row : content.path("disconnected").path("sample")) {
            if (row.path("id").asText().equals(disconnected.toString())) {
                disconnectedContainsOrphan = true;
            }
        }
        assertThat(disconnectedContainsOrphan).isTrue();

        JsonNode clusters = content.path("duplicate_clusters");
        assertThat(clusters.isArray()).isTrue();
        assertThat(clusters.size()).isEqualTo(1);
        JsonNode pair = clusters.get(0);
        assertThat(Set.of(pair.path("cell_a").path("id").asText(), pair.path("cell_b").path("id").asText()))
                .containsExactlyInAnyOrder(dupA.toString(), dupB.toString());
        assertThat(pair.path("similarity").asDouble()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void includeFilterReturnsOnlyRequestedSections() throws Exception {
        seedCell("some cell", null, "facts", "t");

        JsonNode content = contentJson(callToolContent("data_quality_report", Map.of(
                "include", List.of("disconnected")
        )));

        assertThat(content.has("disconnected")).isTrue();
        assertThat(content.has("unclassified")).isFalse();
        assertThat(content.has("duplicate_clusters")).isFalse();
    }

    @Test
    void highThresholdStillFindsIdenticalPair() throws Exception {
        seedCell("identical content here", "realmx", "facts", "topic-a");
        seedCell("identical content here", "realmx", "facts", "topic-b");

        JsonNode content = contentJson(callToolContent("data_quality_report", Map.of(
                "include", List.of("duplicate_clusters"),
                "threshold", 0.99
        )));

        assertThat(content.path("duplicate_clusters").size()).isEqualTo(1);
    }

    @Test
    void invalidIncludeValueIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "data_quality_report",
                                        "arguments", Map.of("include", List.of("bogus"))
                                )
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thresholdOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "data_quality_report",
                                        "arguments", Map.of("threshold", 0.4)
                                )
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "data_quality_report",
                                        "arguments", Map.of("limit", 500)
                                )
                        ))))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return EMBEDDING_CLIENT;
        }

        @Bean
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }
    }
}
