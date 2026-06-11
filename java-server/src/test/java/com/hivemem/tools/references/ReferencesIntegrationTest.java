package com.hivemem.tools.references;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.ConfidenceThresholds;
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
import org.jooq.Record;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = ReferencesIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class ReferencesIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);

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

    // --- Python: test_add_reference ---
    @Test
    void addReferenceWithAllFieldsPersistsEveryColumn() {
        Map<String, Object> result = writeToolService.addReference(
                "GraphRAG Survey 2024",
                "https://arxiv.org/abs/2024.xxxxx",
                "Zhang et al.",
                "paper",
                "read",
                "Comprehensive survey",
                List.of("graph", "rag"),
                2
        );

        assertThat(result).containsEntry("title", "GraphRAG Survey 2024");
        assertThat(result).containsEntry("status", "read");
        assertThat(result).containsKey("id");

        UUID refId = UUID.fromString((String) result.get("id"));
        Record row = dslContext.fetchOne("""
                SELECT title, url, author, ref_type, status, notes, tags, importance
                FROM references_
                WHERE id = ?
                """, refId);
        assertThat(row).isNotNull();
        assertThat(row.get("title", String.class)).isEqualTo("GraphRAG Survey 2024");
        assertThat(row.get("url", String.class)).isEqualTo("https://arxiv.org/abs/2024.xxxxx");
        assertThat(row.get("author", String.class)).isEqualTo("Zhang et al.");
        assertThat(row.get("ref_type", String.class)).isEqualTo("paper");
        assertThat(row.get("status", String.class)).isEqualTo("read");
        assertThat(row.get("notes", String.class)).isEqualTo("Comprehensive survey");
        assertThat(row.get("tags", String[].class)).containsExactly("graph", "rag");
        assertThat(row.get("importance", Integer.class)).isEqualTo(2);
    }

    // --- Python: test_reading_list_shows_unread ---
    @Test
    void readingListReturnsOnlyUnreadReferences() {
        writeToolService.addReference("Unread Paper", null, null, "paper", "unread", null, List.of(), 1);
        writeToolService.addReference("Read Paper", null, null, "paper", "read", null, List.of(), null);
        writeToolService.addReference("Archived Paper", null, null, "paper", "archived", null, List.of(), null);

        List<Map<String, Object>> reading = readToolService.readingList(null, 20);

        assertThat(reading).hasSize(1);
        assertThat(reading.getFirst()).containsEntry("title", "Unread Paper");
        assertThat(reading.getFirst()).containsEntry("status", "unread");
    }

    // --- Python: test_reading_list_shows_reading ---
    @Test
    void readingListReturnsInProgressReferences() {
        writeToolService.addReference("Currently Reading", null, null, "book", "reading", null, List.of(), null);

        List<Map<String, Object>> reading = readToolService.readingList(null, 20);

        assertThat(reading).hasSize(1);
        assertThat(reading.getFirst()).containsEntry("title", "Currently Reading");
        assertThat(reading.getFirst()).containsEntry("status", "reading");
    }

    // --- Python: test_reading_list_filter_by_type ---
    @Test
    void readingListFiltersByRefType() {
        writeToolService.addReference("Unread Article", null, null, "article", "unread", null, List.of(), null);
        writeToolService.addReference("Unread Book", null, null, "book", "unread", null, List.of(), null);

        List<Map<String, Object>> articles = readToolService.readingList("article", 20);

        assertThat(articles).hasSize(1);
        assertThat(articles.getFirst()).containsEntry("ref_type", "article");
        assertThat(articles.getFirst()).containsEntry("title", "Unread Article");
    }

    // --- User request: link one reference to multiple drawers ---
    @Test
    void linkOneReferenceToMultipleDrawers() {
        Map<String, Object> drawerA = writeToolService.addCell(
                WRITER, "Drawer A content", "eng", "facts", "refs", "system",
                List.of(), 1, "Drawer A", List.of(), null, null, "committed",
                OffsetDateTime.parse("2026-04-10T10:00:00Z"), null);
        Map<String, Object> drawerB = writeToolService.addCell(
                WRITER, "Drawer B content", "eng", "facts", "refs", "system",
                List.of(), 1, "Drawer B", List.of(), null, null, "committed",
                OffsetDateTime.parse("2026-04-10T10:01:00Z"), null);

        Map<String, Object> ref = writeToolService.addReference(
                "Shared Reference", "https://example.com", null, "paper", "unread", null, List.of(), 2);

        UUID drawerAId = UUID.fromString((String) drawerA.get("id"));
        UUID drawerBId = UUID.fromString((String) drawerB.get("id"));
        UUID refId = UUID.fromString((String) ref.get("id"));

        Map<String, Object> linkA = writeToolService.linkReference(drawerAId, refId, "source");
        Map<String, Object> linkB = writeToolService.linkReference(drawerBId, refId, "extends");

        assertThat(linkA).containsEntry("cell_id", drawerAId.toString());
        assertThat(linkA).containsEntry("reference_id", refId.toString());
        assertThat(linkA).containsEntry("relation", "source");
        assertThat(linkB).containsEntry("cell_id", drawerBId.toString());
        assertThat(linkB).containsEntry("reference_id", refId.toString());
        assertThat(linkB).containsEntry("relation", "extends");

        long linkCount = dslContext.fetchOne("""
                SELECT count(*) AS cnt
                FROM cell_references
                WHERE reference_id = ?
                """, refId).get("cnt", Long.class);
        assertThat(linkCount).isEqualTo(2L);

        // Reading list should show linked_drawers = 2 for this reference
        List<Map<String, Object>> reading = readToolService.readingList(null, 20);
        assertThat(reading).hasSize(1);
        assertThat(reading.getFirst()).containsEntry("linked_cells", 2L);
    }

    // --- User request: reading list respects limit parameter ---
    @Test
    void readingListRespectsLimitParameter() {
        writeToolService.addReference("Ref 1", null, null, "paper", "unread", null, List.of(), 1);
        writeToolService.addReference("Ref 2", null, null, "paper", "unread", null, List.of(), 2);
        writeToolService.addReference("Ref 3", null, null, "paper", "unread", null, List.of(), 3);

        List<Map<String, Object>> limited = readToolService.readingList(null, 2);

        assertThat(limited).hasSize(2);
        // Ordered by importance ASC, so importance=1 first, then 2
        assertThat(limited.get(0)).containsEntry("title", "Ref 1");
        assertThat(limited.get(1)).containsEntry("title", "Ref 2");
    }

    // --- Python: test_ref_type_check_constraint ---
    @Test
    void invalidRefTypeIsRejectedByCheckConstraint() {
        assertThatThrownBy(() ->
                writeToolService.addReference("Bad type", null, null, "invalid_type", "unread", null, List.of(), null))
                .isInstanceOf(Exception.class);

        long count = dslContext.fetchOne("SELECT count(*) AS cnt FROM references_").get("cnt", Long.class);
        assertThat(count).isZero();
    }

    // Not ported: test_link_reference_to_cell — already covered in WriteToolsIntegrationTest.writerCanAddReferenceAndLinkItToDrawer

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
