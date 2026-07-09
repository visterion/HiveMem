package com.hivemem.tools.read;

import com.hivemem.auth.RateLimiter;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(EntityOverviewCanonicalTest.TestConfig.class)
@Testcontainers
class EntityOverviewCanonicalTest {

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
    private DSLContext dsl;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private RateLimiter rateLimiter;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dsl.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cell_attachments, attachments, cells, kg_entity CASCADE");
    }

    @Test
    void entityOverviewResolvesAliasToCanonical() {
        new KgEntityRepository(dsl).upsert("HiveMem", List.of("hivemem-mcp-server"), "t");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem','tool_count','50',1.0,'committed','s')");

        Map<String, Object> overview = readToolService.entityOverview("hivemem-mcp-server", 5);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) overview.get("facts");
        assertThat(facts).anySatisfy(f -> assertThat(f).containsEntry("predicate", "tool_count"));
    }

    @Test
    void entityOverviewQuickDepthResolvesAliasToCanonical() {
        new KgEntityRepository(dsl).upsert("HiveMem", List.of("hivemem-mcp-server"), "t");
        dsl.execute("INSERT INTO facts (subject, predicate, \"object\", confidence, status, created_by) VALUES ('HiveMem','tool_count','50',1.0,'committed','s')");

        Map<String, Object> overview = readToolService.entityOverview("hivemem-mcp-server", 5, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) overview.get("facts");
        assertThat(facts).anySatisfy(f -> assertThat(f).containsEntry("predicate", "tool_count"));
        assertThat((List<?>) overview.get("cells")).isEmpty();
        assertThat((List<?>) overview.get("tunnels")).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
