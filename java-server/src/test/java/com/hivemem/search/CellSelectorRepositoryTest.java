package com.hivemem.search;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = CellSelectorRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CellSelectorRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CellSelectorRepository.class, TestConfig.class})
    static class TestApplication {}

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
    CellSelectorRepository cellSelectorRepository;

    @Autowired
    DSLContext dslContext;

    @BeforeEach
    void seed() {
        dslContext.execute("DELETE FROM cells WHERE topic = 'cs-test'");
    }

    private void insertCell(UUID id, String content, String realm, String status, boolean softDeleted) {
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at, valid_until) " +
                        "VALUES (?, ?, ?, 'facts', 'cs-test', ?, ?, now(), now(), ?::timestamptz)",
                id, content, realm, new String[]{}, status,
                softDeleted ? java.time.OffsetDateTime.now().toString() : null);
    }

    @Test
    void selectsIdsByRealm() {
        insertCell(UUID.randomUUID(), "cell one", "hivemem", "committed", false);
        insertCell(UUID.randomUUID(), "cell two", "hivemem", "committed", false);
        insertCell(UUID.randomUUID(), "cell three", "work", "committed", false);

        var sel = new CellSelector("hivemem", null, null, null, null, null, null);
        var rows = cellSelectorRepository.selectIds(sel, 100, 0);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsKeys("id", "realm", "signal", "topic");
        assertThat(cellSelectorRepository.countMatches(sel)).isEqualTo(2);
    }

    @Test
    void realmNoneSentinelMatchesNullRealm() {
        insertCell(UUID.randomUUID(), "null realm cell", null, "committed", false);
        insertCell(UUID.randomUUID(), "hivemem cell", "hivemem", "committed", false);

        var sel = new CellSelector("none", null, null, null, null, null, null);
        assertThat(cellSelectorRepository.countMatches(sel)).isEqualTo(1);
    }

    @Test
    void realmInMatchesMultipleRealmsIncludingNone() {
        insertCell(UUID.randomUUID(), "a cell", "a", "committed", false);
        insertCell(UUID.randomUUID(), "b cell", "b", "committed", false);
        insertCell(UUID.randomUUID(), "c cell", "c", "committed", false);
        insertCell(UUID.randomUUID(), "null cell", null, "committed", false);

        var sel = new CellSelector(null, List.of("a", "b", "none"), null, null, null, null, null);
        assertThat(cellSelectorRepository.countMatches(sel)).isEqualTo(3);
    }

    @Test
    void queryFiltersViaFullText() {
        insertCell(UUID.randomUUID(), "quarterly attachment report", "hivemem", "committed", false);
        insertCell(UUID.randomUUID(), "unrelated content here", "hivemem", "committed", false);

        var sel = new CellSelector(null, null, null, null, null, "attachment", null);
        assertThat(cellSelectorRepository.countMatches(sel)).isEqualTo(1);
    }

    @Test
    void excludesSoftDeletedAndDefaultsToCommitted() {
        insertCell(UUID.randomUUID(), "active committed cell", "hivemem", "committed", false);
        insertCell(UUID.randomUUID(), "soft deleted cell", "hivemem", "committed", true);
        insertCell(UUID.randomUUID(), "pending cell", "hivemem", "pending", false);

        var sel = new CellSelector(null, null, null, null, null, null, null);
        assertThat(cellSelectorRepository.countMatches(sel)).isEqualTo(1);

        var pendingSel = new CellSelector(null, null, null, null, null, null, "pending");
        assertThat(cellSelectorRepository.countMatches(pendingSel)).isEqualTo(1);
    }

    @Test
    void paginationAndOrderingAreStable() {
        for (int i = 0; i < 5; i++) {
            insertCell(UUID.randomUUID(), "paginated cell " + i, "hivemem", "committed", false);
        }
        var sel = new CellSelector("hivemem", null, null, null, null, null, null);
        var page1 = cellSelectorRepository.selectIds(sel, 2, 0);
        var page2 = cellSelectorRepository.selectIds(sel, 2, 2);
        var page3 = cellSelectorRepository.selectIds(sel, 2, 4);

        var allIds = new java.util.LinkedHashSet<Object>();
        page1.forEach(r -> allIds.add(r.get("id")));
        page2.forEach(r -> allIds.add(r.get("id")));
        page3.forEach(r -> allIds.add(r.get("id")));

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).hasSize(1);
        assertThat(allIds).hasSize(5);
    }

    @Test
    void selectAllIdsCapsAndOrdersByCreatedAtDesc() {
        for (int i = 0; i < 5; i++) {
            insertCell(UUID.randomUUID(), "cap cell " + i, "hivemem", "committed", false);
        }
        var sel = new CellSelector("hivemem", null, null, null, null, null, null);
        List<UUID> ids = cellSelectorRepository.selectAllIds(sel, 3);
        assertThat(ids).hasSize(3);
    }
}
