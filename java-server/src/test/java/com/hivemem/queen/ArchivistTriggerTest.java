package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
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

import java.util.Arrays;
import java.util.UUID;

import static org.mockito.Mockito.*;

// Mirrors the Testcontainers Postgres integration setup used by QueenRepositoryInboxTest:
// an autowired DSLContext `db` against a Flyway-migrated schema. There is no shared
// abstract base class in this project -- each IT test duplicates this boilerplate.
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ArchivistTriggerTest.TestConfig.class)
class ArchivistTriggerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
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

    @Autowired DSLContext db;

    @BeforeEach
    void cleanInboxAndWorkCells() {
        db.execute("DELETE FROM cells WHERE realm IN ('inbox', 'work')");
    }

    private ArchivistTrigger trigger(VistierieAgentClient client, int debounceSeconds) {
        QueenProperties props = new QueenProperties();
        props.setEnabled(true);
        props.setArchivistDebounceSeconds(debounceSeconds);
        return new ArchivistTrigger(db, client, props);
    }

    private UUID insert(String realm, String... tags) {
        UUID id = UUID.randomUUID();
        String arr = tags.length == 0 ? "'{}'" :
                "ARRAY[" + String.join(",", Arrays.stream(tags).map(t -> "'" + t + "'").toList()) + "]::text[]";
        db.execute("INSERT INTO cells (id, content, realm, signal, topic, status, tags, created_at) "
                + "VALUES (?, 'x', ?, 'facts', 't', 'committed', " + arr + ", now())", id, realm);
        return id;
    }

    @Test
    void firesForReadyInboxCell() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        ArchivistTrigger t = trigger(client, 0);
        t.maybeTrigger(insert("inbox"));
        verify(client).triggerRun(eq("inbox-archivist"), anyMap());
    }

    @Test
    void doesNotFireWhileNeedsSummaryPending() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        ArchivistTrigger t = trigger(client, 0);
        t.maybeTrigger(insert("inbox", "needs_summary"));
        verifyNoInteractions(client);
    }

    @Test
    void firesForTerminalOcrFailEvenWithOcrPending() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        ArchivistTrigger t = trigger(client, 0);
        t.maybeTrigger(insert("inbox", "ocr_pending", "ocr_failed_permanent"));
        verify(client).triggerRun(eq("inbox-archivist"), anyMap());
    }

    @Test
    void doesNotFireForNonInbox() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        ArchivistTrigger t = trigger(client, 0);
        t.maybeTrigger(insert("work"));
        verifyNoInteractions(client);
    }

    @Test
    void debounceSuppressesSecondTriggerInWindow() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        ArchivistTrigger t = trigger(client, 3600); // 1h window
        t.maybeTrigger(insert("inbox"));
        t.maybeTrigger(insert("inbox"));
        verify(client, times(1)).triggerRun(eq("inbox-archivist"), anyMap());
    }
}
