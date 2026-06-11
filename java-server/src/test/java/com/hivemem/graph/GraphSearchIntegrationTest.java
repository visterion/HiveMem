package com.hivemem.graph;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated integration tests for graph search: traverse (bidirectional, relation filter,
 * depth control, status filtering) and quick_facts (active-only, subject/object match, empty).
 */
@SpringBootTest(
        classes = GraphSearchIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class GraphSearchIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final AuthPrincipal AGENT = new AuthPrincipal("agent-1", AuthRole.AGENT);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T09:00:00Z");

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
    private WriteToolService writeToolService;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    // ---- traverse: bidirectional ----

    @Test
    void traverseFromTargetReachesSourceViaBidirectionalExpansion() {
        // A -> B tunnel exists; traversing from B should discover A
        Map<String, Object> drawerA = createDrawer("Bidirectional Source");
        Map<String, Object> drawerB = createDrawer("Bidirectional Target");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);

        writeToolService.addTunnel(WRITER, idA, idB, "builds_on", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idB, 1, null);

        Set<UUID> reachable = collectAllDrawerIds(results);
        assertThat(reachable).contains(idA);
    }

    // ---- traverse: relation filter ----

    @Test
    void traverseWithRelationFilterReturnsOnlyMatchingEdges() {
        Map<String, Object> drawerA = createDrawer("Filter Node A");
        Map<String, Object> drawerB = createDrawer("Filter Node B");
        Map<String, Object> drawerC = createDrawer("Filter Node C");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);
        UUID idC = id(drawerC);

        writeToolService.addTunnel(WRITER, idA, idB, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idA, idC, "builds_on", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 3, "related_to");

        Set<UUID> reachable = collectToDrawerIds(results);
        assertThat(reachable).contains(idB);
        assertThat(reachable).doesNotContain(idC);
    }

    @Test
    void traverseWithRelationFilterFollowsMultiHopChain() {
        // A -builds_on-> B -builds_on-> C -related_to-> D
        // Filter on builds_on should reach B and C but not D
        Map<String, Object> drawerA = createDrawer("Chain A");
        Map<String, Object> drawerB = createDrawer("Chain B");
        Map<String, Object> drawerC = createDrawer("Chain C");
        Map<String, Object> drawerD = createDrawer("Chain D");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);
        UUID idC = id(drawerC);
        UUID idD = id(drawerD);

        writeToolService.addTunnel(WRITER, idA, idB, "builds_on", null, "committed");
        writeToolService.addTunnel(WRITER, idB, idC, "builds_on", null, "committed");
        writeToolService.addTunnel(WRITER, idC, idD, "related_to", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 5, "builds_on");

        Set<UUID> reachable = collectAllDrawerIds(results);
        assertThat(reachable).contains(idB, idC);
        assertThat(reachable).doesNotContain(idD);
    }

    // ---- traverse: pending tunnels ----

    @Test
    void traverseSkipsPendingTunnels() {
        Map<String, Object> drawerA = createDrawer("Pending Source");
        Map<String, Object> drawerB = createDrawer("Pending Target");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);

        // Agent role forces status=pending on addTunnel
        writeToolService.addTunnel(AGENT, idA, idB, "related_to", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 1, null);

        assertThat(results).isEmpty();
    }

    // ---- traverse: removed tunnels ----

    @Test
    void traverseSkipsRemovedTunnels() {
        Map<String, Object> drawerA = createDrawer("Removed Source");
        Map<String, Object> drawerB = createDrawer("Removed Target");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);

        Map<String, Object> tunnel = writeToolService.addTunnel(WRITER, idA, idB, "related_to", null, "committed");
        UUID tunnelId = UUID.fromString((String) tunnel.get("id"));
        writeToolService.removeTunnel(tunnelId);

        List<Map<String, Object>> results = readToolService.traverse(idA, 1, null);

        assertThat(results).isEmpty();
    }

    // ---- traverse: depth control ----

    @Test
    void traverseWithDepthOneReturnsOnlyImmediateNeighbors() {
        // A -> B -> C; depth=1 from A should find B but not C
        Map<String, Object> drawerA = createDrawer("Depth1 A");
        Map<String, Object> drawerB = createDrawer("Depth1 B");
        Map<String, Object> drawerC = createDrawer("Depth1 C");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);
        UUID idC = id(drawerC);

        writeToolService.addTunnel(WRITER, idA, idB, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idB, idC, "related_to", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 1, null);

        Set<UUID> reachable = collectToDrawerIds(results);
        assertThat(reachable).contains(idB);
        assertThat(reachable).doesNotContain(idC);
    }

    @Test
    void traverseWithDepthZeroReturnsDirectNeighborsOnly() {
        // Java traverse semantics: the recursive CTE seeds with depth=1 (direct neighbors)
        // and only recurses WHERE depth < maxDepth. So maxDepth=0 returns the base case
        // (direct neighbors, depth=1) without any further hops.
        Map<String, Object> drawerA = createDrawer("Depth0 A");
        Map<String, Object> drawerB = createDrawer("Depth0 B");
        Map<String, Object> drawerC = createDrawer("Depth0 C");
        UUID idA = id(drawerA);
        UUID idB = id(drawerB);
        UUID idC = id(drawerC);

        writeToolService.addTunnel(WRITER, idA, idB, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idB, idC, "related_to", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 0, null);

        Set<UUID> reachable = collectToDrawerIds(results);
        assertThat(reachable).contains(idB);
        assertThat(reachable).doesNotContain(idC);
    }

    // ---- quick_facts: active only ----

    @Test
    void quickFactsReturnsOnlyActiveFactsNotInvalidated() {
        // Add two facts, invalidate one -- quick_facts should only return the active one
        Map<String, Object> activeFact = writeToolService.kgAdd(
                WRITER, "GraphEntity", "uses", "PostgreSQL", 1.0d, null, "committed", BASE_TIME, null);
        Map<String, Object> invalidatedFact = writeToolService.kgAdd(
                WRITER, "GraphEntity", "uses", "SQLite", 0.8d, null, "committed", BASE_TIME.plusSeconds(1), null);
        UUID invalidatedId = UUID.fromString((String) invalidatedFact.get("id"));

        writeToolService.kgInvalidate(invalidatedId);

        List<Map<String, Object>> facts = readToolService.quickFacts("GraphEntity");

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().get("object")).isEqualTo("PostgreSQL");
    }

    @Test
    void quickFactsFindsEntityAsSubjectAndAsObject() {
        // "GraphEntity2 uses Java" and "Viktor created GraphEntity2"
        writeToolService.kgAdd(WRITER, "GraphEntity2", "uses", "Java", 1.0d, null, "committed", BASE_TIME, null);
        writeToolService.kgAdd(WRITER, "Viktor", "created", "GraphEntity2", 0.9d, null, "committed", BASE_TIME.plusSeconds(1), null);

        List<Map<String, Object>> facts = readToolService.quickFacts("GraphEntity2");

        assertThat(facts).hasSize(2);
        Set<String> subjects = new HashSet<>();
        Set<String> objects = new HashSet<>();
        for (Map<String, Object> fact : facts) {
            subjects.add((String) fact.get("subject"));
            objects.add((String) fact.get("object"));
        }
        // As subject
        assertThat(subjects).contains("GraphEntity2");
        assertThat(objects).contains("Java");
        // As object
        assertThat(subjects).contains("Viktor");
        assertThat(objects).contains("GraphEntity2");
    }

    @Test
    void quickFactsReturnsEmptyForUnknownEntity() {
        List<Map<String, Object>> facts = readToolService.quickFacts("CompletelyUnknownEntity");

        assertThat(facts).isEmpty();
    }

    // ---- helpers ----

    private Map<String, Object> createDrawer(String content) {
        return writeToolService.addCell(
                WRITER,
                content,
                "test",
                "facts",
                null,
                "system",
                List.of(),
                1,
                null,
                List.of(),
                null,
                null,
                "committed",
                BASE_TIME,
                null
        );
    }

    private static UUID id(Map<String, Object> result) {
        return UUID.fromString((String) result.get("id"));
    }

    private static Set<UUID> collectAllDrawerIds(List<Map<String, Object>> edges) {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            ids.add(UUID.fromString((String) edge.get("from_cell")));
            ids.add(UUID.fromString((String) edge.get("to_cell")));
        }
        return ids;
    }

    private static Set<UUID> collectToDrawerIds(List<Map<String, Object>> edges) {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            ids.add(UUID.fromString((String) edge.get("to_cell")));
        }
        return ids;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            ReadToolService.class,
            DocumentListRepository.class,
            MediaListRepository.class,
            FacetRepository.class,
            AttachmentRepository.class,
            SearchWeightsProperties.class,
            ConfidenceThresholds.class,
            CellReadRepository.class,
            CellSearchRepository.class,
            KgSearchRepository.class,
            AdminToolRepository.class,
            OpLogWriter.class,
            InstanceConfig.class,
            TestConfig.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }

        @Bean
        AdminToolService adminToolService(AdminToolRepository adminToolRepository) {
            return new AdminToolService(adminToolRepository, new com.hivemem.embedding.EmbeddingMigrationService(
                    new FixedEmbeddingClient(), null) {
                @Override
                public void run(org.springframework.boot.ApplicationArguments args) {}
            });
        }

        @Bean
        PushDispatcher pushDispatcher() {
            return org.mockito.Mockito.mock(PushDispatcher.class);
        }
    }
}
