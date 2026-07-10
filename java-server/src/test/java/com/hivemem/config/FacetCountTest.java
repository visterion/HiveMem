package com.hivemem.config;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.FacetRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(FacetCountTest.TestConfig.class)
class FacetCountTest {

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
    FacetRepository facetRepository;

    @Autowired
    DSLContext dslContext;

    static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0001-000000000001");
    static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0001-000000000002");
    static final UUID ID_3 = UUID.fromString("00000000-0000-0000-0001-000000000003");
    static final UUID ID_4 = UUID.fromString("00000000-0000-0000-0001-000000000004");

    @BeforeEach
    void seed() {
        dslContext.execute("DELETE FROM facts WHERE source_id IN (SELECT id FROM cells WHERE realm = 'fdocs')");
        dslContext.execute("DELETE FROM cells WHERE realm = 'fdocs' AND topic = 't'");
        // 2 committed with tag contract (years 2024, 2025)
        seed(ID_1, "contract alpha document", new String[]{"contract"}, "committed", "2024");
        seed(ID_2, "contract beta document", new String[]{"contract"}, "committed", "2025");
        // 1 committed with tag invoice (2025)
        seed(ID_3, "invoice gamma document", new String[]{"invoice"}, "committed", "2025");
        // 1 pending with tag contract (2025)
        seed(ID_4, "contract delta document pending", new String[]{"contract"}, "pending", "2025");
    }

    private void seed(UUID id, String content, String[] tags, String status, String year) {
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, ?, 'fdocs', 'facts', 't', ?, ?, now(), (?::date))",
                id, content, tags, status, year + "-03-01");
    }

    @Test
    void facetCountReturnsCorrectTagStatusAndYearCounts() {
        Map<String, List<Map<String, Object>>> result = facetRepository.facetCounts(
                "fdocs", null, null, null, null, null, null,
                List.of("tag", "status", "year"), 20);

        // tag facet: contract:3 (sorted desc), invoice:1
        List<Map<String, Object>> tagFacet = result.get("tag");
        assertThat(tagFacet).isNotNull();
        assertThat(tagFacet).hasSizeGreaterThanOrEqualTo(2);
        assertThat(tagFacet.get(0).get("value")).isEqualTo("contract");
        assertThat(((Number) tagFacet.get(0).get("count")).intValue()).isEqualTo(3);
        assertThat(tagFacet.stream().anyMatch(m -> "invoice".equals(m.get("value")) && ((Number) m.get("count")).intValue() == 1)).isTrue();

        // status facet: committed:3, pending:1
        List<Map<String, Object>> statusFacet = result.get("status");
        assertThat(statusFacet).isNotNull();
        assertThat(statusFacet.stream().anyMatch(m -> "committed".equals(m.get("value")) && ((Number) m.get("count")).intValue() == 3)).isTrue();
        assertThat(statusFacet.stream().anyMatch(m -> "pending".equals(m.get("value")) && ((Number) m.get("count")).intValue() == 1)).isTrue();

        // year facet: 2025:3, 2024:1
        List<Map<String, Object>> yearFacet = result.get("year");
        assertThat(yearFacet).isNotNull();
        assertThat(yearFacet.get(0).get("value")).isEqualTo("2025");
        assertThat(((Number) yearFacet.get(0).get("count")).intValue()).isEqualTo(3);
        assertThat(yearFacet.stream().anyMatch(m -> "2024".equals(m.get("value")) && ((Number) m.get("count")).intValue() == 1)).isTrue();
    }

    @Test
    void yearFacetRespectsLimit() {
        // B4 (LOW): LIMIT was only appended for the tag/fact:* branches; status/realm/signal/year
        // must be bounded by `limit` too, otherwise a facet with more distinct buckets than the
        // caller's limit silently returns them all.
        dslContext.execute("DELETE FROM cells WHERE realm = 'flimit'");
        for (int i = 0; i < 5; i++) {
            dslContext.execute(
                    "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                    "VALUES (?, 'doc', 'flimit', 'facts', 'lt', '{}', 'committed', now(), (?::date))",
                    UUID.randomUUID(), (2020 + i) + "-01-01");
        }

        Map<String, List<Map<String, Object>>> result = facetRepository.facetCounts(
                "flimit", null, null, null, null, null, null,
                List.of("year"), 2);

        assertThat(result.get("year")).hasSize(2);
    }

    @Test
    void unknownFieldThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> facetRepository.facetCounts(
                "fdocs", null, null, null, null, null, null,
                List.of("evil"), 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evil");
    }

    // ── fact-facet helpers ────────────────────────────────────────────────────

    private void seedDoc(UUID id, String realm) {
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'doc content', ?, 'facts', 'fact_t', '{}', 'committed', now(), now())",
                id, realm);
    }

    private void seedFact(UUID sourceId, String predicate, String object) {
        dslContext.execute(
                "INSERT INTO facts (subject, predicate, \"object\", confidence, source_id, status, valid_from) " +
                "VALUES ('doc', ?, ?, 1.0, ?, 'committed', now())",
                predicate, object, sourceId);
    }

    // ── fact-facet tests ──────────────────────────────────────────────────────

    @Test
    void factFacetCountsByPredicateObject() {
        UUID d1 = UUID.randomUUID(), d2 = UUID.randomUUID(), d3 = UUID.randomUUID();
        seedDoc(d1, "fdocs"); seedDoc(d2, "fdocs"); seedDoc(d3, "fdocs");
        seedFact(d1, "vendor", "HUK-COBURG");
        seedFact(d2, "vendor", "HUK-COBURG");
        seedFact(d3, "vendor", "Telekom");

        var fc = facetRepository.facetCounts("fdocs", null, null, null, null, null, null,
                java.util.List.of("fact:vendor"), 20);

        var vendor = fc.get("fact:vendor");
        assertThat(vendor).isNotNull();
        // HUK first (count 2), Telekom (count 1)
        assertThat(vendor.get(0).get("value")).isEqualTo("HUK-COBURG");
        assertThat(((Number) vendor.get(0).get("count")).intValue()).isEqualTo(2);
    }

    @Test
    void unknownFactPredicateRejected() {
        assertThatThrownBy(() -> facetRepository.facetCounts("fdocs", null, null, null, null, null, null,
                java.util.List.of("fact:evil"), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── realm "none" sentinel tests ────────────────────────────────────────────

    @Test
    void realmNoneSentinelCountsOnlyNullRealmCells() {
        // Clean up any null-realm cells left by other tests in this class (order is not guaranteed).
        dslContext.execute("DELETE FROM cells WHERE realm IS NULL");
        dslContext.execute("DELETE FROM cells WHERE topic = 'norealm-t'");
        for (int i = 0; i < 2; i++) {
            dslContext.execute(
                    "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                    "VALUES (?, 'no realm doc', NULL, 'facts', 'norealm-t', '{}', 'committed', now(), now())",
                    UUID.randomUUID());
        }
        for (int i = 0; i < 3; i++) {
            dslContext.execute(
                    "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                    "VALUES (?, 'has realm doc', 'norealm-x', 'facts', 'norealm-t', '{}', 'committed', now(), now())",
                    UUID.randomUUID());
        }

        var result = facetRepository.facetCounts("none", null, null, null, null, null, null, List.of("status"), 10);
        assertThat(((Number) result.get("status").get(0).get("count")).intValue()).isEqualTo(2);
    }

    @Test
    void realmNoneSentinelDoesNotMatchLiteralNoneRealm() {
        // The sentinel "none" must only match NULL realms, never a cell whose realm column is
        // literally the string 'none' (bypassing normal add_cell realm validation via direct SQL).
        dslContext.execute("DELETE FROM cells WHERE realm IS NULL OR realm = 'none'");
        dslContext.execute("DELETE FROM cells WHERE topic = 'literal-none-t'");
        UUID nullRealmDoc = UUID.randomUUID();
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'no realm doc', NULL, 'facts', 'literal-none-t', '{}', 'committed', now(), now())",
                nullRealmDoc);
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'literal none realm doc', 'none', 'facts', 'literal-none-t', '{}', 'committed', now(), now())",
                UUID.randomUUID());

        var result = facetRepository.facetCounts("none", null, null, null, null, null, null, List.of("status"), 10);
        assertThat(((Number) result.get("status").get(0).get("count")).intValue()).isEqualTo(1);
    }

    @Test
    void factFacetRespectsNoneSentinel() {
        dslContext.execute("DELETE FROM facts WHERE source_id IN (SELECT id FROM cells WHERE topic = 'norealm-fact-t')");
        dslContext.execute("DELETE FROM cells WHERE topic = 'norealm-fact-t'");

        UUID noRealmDoc = UUID.randomUUID();
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'doc content', NULL, 'facts', 'norealm-fact-t', '{}', 'committed', now(), now())",
                noRealmDoc);
        UUID hasRealmDoc = UUID.randomUUID();
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'doc content', 'norealm-fact-x', 'facts', 'norealm-fact-t', '{}', 'committed', now(), now())",
                hasRealmDoc);
        seedFact(noRealmDoc, "vendor", "NoRealmVendor");
        seedFact(hasRealmDoc, "vendor", "OtherVendor");

        var result = facetRepository.facetCounts("none", null, null, null, null, null, null, List.of("fact:vendor"), 10);
        var vendor = result.get("fact:vendor");
        assertThat(vendor).hasSize(1);
        assertThat(vendor.get(0).get("value")).isEqualTo("NoRealmVendor");
        assertThat(((Number) vendor.get(0).get("count")).intValue()).isEqualTo(1);
    }
}
