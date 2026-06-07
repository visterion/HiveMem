package com.hivemem.config;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.savedsearch.SavedSearchRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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
 * Integration tests for the saved_searches table and SavedSearchRepository.
 *
 * Covers: save → list (returns it, scoped to owner) → delete → list (gone).
 * Also verifies that a different owner does not see another user's saved searches.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SavedSearchTest.TestConfig.class)
class SavedSearchTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

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
    SavedSearchRepository savedSearchRepository;

    @Autowired
    DSLContext dslContext;

    @BeforeEach
    void cleanup() {
        dslContext.execute("DELETE FROM saved_searches");
    }

    // ─── save → list → delete round-trip ────────────────────────────────────

    @Test
    void saveAndListReturnsTheSavedSearch() {
        String owner = "alice";
        String filter = "{\"realm\":\"documents\"}";

        Map<String, Object> saved = savedSearchRepository.save(owner, "My Search", filter);

        assertThat(saved).isNotNull();
        assertThat(saved.get("name")).isEqualTo("My Search");
        // filter is returned as JSON text; must contain the realm key
        assertThat(saved.get("filter").toString()).contains("documents");
        assertThat(saved.get("id")).isNotNull();
        assertThat(saved.get("created_at")).isNotNull();

        List<Map<String, Object>> list = savedSearchRepository.listByOwner(owner);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name")).isEqualTo("My Search");
        assertThat(list.get(0).get("filter").toString()).contains("documents");
    }

    @Test
    void listReturnsOnlyActiveRowsOrderedByName() {
        savedSearchRepository.save("bob", "Zebra", "{}");
        savedSearchRepository.save("bob", "Alpha", "{}");
        savedSearchRepository.save("bob", "Middle", "{}");

        List<Map<String, Object>> list = savedSearchRepository.listByOwner("bob");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("name")).isEqualTo("Alpha");
        assertThat(list.get(1).get("name")).isEqualTo("Middle");
        assertThat(list.get(2).get("name")).isEqualTo("Zebra");
    }

    @Test
    void deleteSoftDeletesAndDisappearsFromList() {
        Map<String, Object> saved = savedSearchRepository.save("alice", "To Delete", "{}");
        UUID id = UUID.fromString(saved.get("id").toString());

        assertThat(savedSearchRepository.listByOwner("alice")).hasSize(1);

        boolean deleted = savedSearchRepository.delete(id, "alice");
        assertThat(deleted).isTrue();

        assertThat(savedSearchRepository.listByOwner("alice")).isEmpty();
    }

    @Test
    void deleteReturnsFalseWhenNotFoundOrWrongOwner() {
        Map<String, Object> saved = savedSearchRepository.save("alice", "Secret", "{}");
        UUID id = UUID.fromString(saved.get("id").toString());

        // Wrong owner cannot delete
        boolean deleted = savedSearchRepository.delete(id, "mallory");
        assertThat(deleted).isFalse();

        // Alice's row still active
        assertThat(savedSearchRepository.listByOwner("alice")).hasSize(1);

        // Non-existent id also returns false
        boolean notFound = savedSearchRepository.delete(UUID.randomUUID(), "alice");
        assertThat(notFound).isFalse();
    }

    // ─── owner scoping ───────────────────────────────────────────────────────

    @Test
    void ownerScopingPreventsSeingOtherUserSearches() {
        savedSearchRepository.save("alice", "Alice's search", "{\"realm\":\"personal\"}");
        savedSearchRepository.save("bob",   "Bob's search",   "{\"realm\":\"work\"}");

        List<Map<String, Object>> aliceList = savedSearchRepository.listByOwner("alice");
        assertThat(aliceList).hasSize(1);
        assertThat(aliceList.get(0).get("name")).isEqualTo("Alice's search");

        List<Map<String, Object>> bobList = savedSearchRepository.listByOwner("bob");
        assertThat(bobList).hasSize(1);
        assertThat(bobList.get(0).get("name")).isEqualTo("Bob's search");
    }

    // ─── upsert by (owner, name) ─────────────────────────────────────────────

    @Test
    void saveWithSameNameReplacesExistingRow() {
        savedSearchRepository.save("alice", "My Search", "{\"realm\":\"a\"}");
        Map<String, Object> second = savedSearchRepository.save("alice", "My Search", "{\"realm\":\"b\"}");

        List<Map<String, Object>> list = savedSearchRepository.listByOwner("alice");
        // Only one active row — the replacement
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("filter").toString()).contains("\"b\"");
        assertThat(second.get("filter").toString()).contains("\"b\"");
    }

    @Test
    void saveWithSameNameDifferentOwnerKeepsBoth() {
        savedSearchRepository.save("alice", "Shared Name", "{\"realm\":\"alice\"}");
        savedSearchRepository.save("bob",   "Shared Name", "{\"realm\":\"bob\"}");

        assertThat(savedSearchRepository.listByOwner("alice")).hasSize(1);
        assertThat(savedSearchRepository.listByOwner("bob")).hasSize(1);
    }

    // ─── filter formats ──────────────────────────────────────────────────────

    @Test
    void emptyFilterDefaultsToEmptyObject() {
        Map<String, Object> saved = savedSearchRepository.save("alice", "Empty Filter", "{}");

        assertThat(saved.get("filter").toString()).isEqualTo("{}");
        List<Map<String, Object>> list = savedSearchRepository.listByOwner("alice");
        assertThat(list.get(0).get("filter").toString()).isEqualTo("{}");
    }

    @Test
    void complexFilterIsPreservedRoundTrip() {
        String filter = "{\"realm\":\"documents\",\"tags\":[\"invoice\"],\"status\":\"committed\"}";
        savedSearchRepository.save("alice", "Complex", filter);

        List<Map<String, Object>> list = savedSearchRepository.listByOwner("alice");
        String returned = list.get(0).get("filter").toString();
        assertThat(returned).contains("documents");
        assertThat(returned).contains("invoice");
        assertThat(returned).contains("committed");
    }
}
