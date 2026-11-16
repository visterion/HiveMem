package com.hivemem.search;

import com.hivemem.HiveMemApplication;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Installs the configured stop-word filter at start. Runs the real startup runner ({@code EmbeddingMigrationService.run}) in a full
 * application context with the real {@code SearchKeywordProperties} binding, then reads back the installed function.
 */
@SpringBootTest(classes = HiveMemApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "hivemem.search.keyword.stop-word-configs=german,english")
@ActiveProfiles("test")
@Testcontainers
@Import(RankedSearchStopWordsWiringIT.TestConfig.class)
class RankedSearchStopWordsWiringIT {

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
    private DSLContext dslContext;

    private String functionDefinition() {
        return dslContext.fetchOne(
                "SELECT pg_get_functiondef(oid) AS def FROM pg_proc WHERE proname = 'ranked_search'")
                .get("def", String.class);
    }

    @Test
    void startInstallsTheConfiguredFilter() {
        assertThat(functionDefinition()).contains(
                "AS lex\n        WHERE length(to_tsvector('german', lex)) > 0 AND length(to_tsvector('english', lex)) > 0");
    }
}
