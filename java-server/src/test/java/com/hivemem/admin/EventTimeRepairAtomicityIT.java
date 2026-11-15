package com.hivemem.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves, with the application's real jOOQ/Spring transaction wiring, that the date update and the
 * op append of one repaired cell commit or roll back together.
 */
@SpringBootTest(
        classes = EventTimeRepairAtomicityIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class EventTimeRepairAtomicityIT {

    private static final UUID FAILING = id(21);
    private static final UUID HEALTHY = id(31);
    private static final String ROOT_DAY = "2024-01-01T00:00:00Z";
    private static final String CHILD_DAY = "2024-02-01T00:00:00Z";
    private static final String EVENT_DAY = "2023-06-15T00:00:00Z";

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

    @Autowired
    private EventTimeRepairService service;

    @Autowired
    private DSLContext dsl;

    @BeforeEach
    void seed() {
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM cells");
        seedLineage(2, 21);
        seedLineage(3, 31);
    }

    @Test
    void aFailedOpAppendRollsTheDateUpdateBack() {
        Map<String, Object> result = service.repair(200);

        assertThat(result.get("failed")).isEqualTo(1);
        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(validFrom(FAILING)).isEqualTo(Instant.parse(CHILD_DAY));
        assertThat(validFrom(HEALTHY)).isEqualTo(Instant.parse(EVENT_DAY));
        assertThat(dsl.fetchOne("SELECT count(*) AS n FROM ops_log WHERE payload->>'cell_id' = ?",
                FAILING.toString()).get("n", Integer.class)).isEqualTo(0);
        assertThat(dsl.fetchOne("SELECT count(*) AS n FROM ops_log WHERE payload->>'cell_id' = ?",
                HEALTHY.toString()).get("n", Integer.class)).isEqualTo(1);
    }

    /** Root dated EVENT_DAY, then an old-rule revision whose valid_from is its own created_at. */
    private void seedLineage(int rootN, int childN) {
        dsl.execute("INSERT INTO cells (id, content, realm, signal, status, created_at, valid_from, valid_until) "
                + "VALUES (?, 'synthetic content', 'notes', 'facts', 'committed', ?::timestamptz, "
                + "?::timestamptz, ?::timestamptz)", id(rootN), ROOT_DAY, EVENT_DAY, CHILD_DAY);
        dsl.execute("INSERT INTO cells (id, parent_id, content, realm, signal, status, created_at, valid_from) "
                + "VALUES (?, ?, 'synthetic content', 'notes', 'facts', 'committed', ?::timestamptz, "
                + "?::timestamptz)", id(childN), id(rootN), CHILD_DAY, CHILD_DAY);
    }

    private Instant validFrom(UUID cellId) {
        return dsl.fetchOne("SELECT valid_from FROM cells WHERE id = ?", cellId)
                .get("valid_from", OffsetDateTime.class).toInstant();
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    @Configuration
    @EnableAutoConfiguration
    @Import({
            EventTimeRepairRepository.class,
            EventTimeRepairService.class,
            InstanceConfig.class,
            TestConfig.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        OpLogWriter opLogWriter(DSLContext dsl, InstanceConfig instanceConfig, ObjectMapper objectMapper) {
            return new OpLogWriter(dsl, instanceConfig, objectMapper) {
                @Override
                public UUID append(String opType, Map<String, Object> payload) {
                    if (FAILING.toString().equals(payload.get("cell_id"))) {
                        throw new IllegalStateException("synthetic op-log failure");
                    }
                    return super.append(opType, payload);
                }
            };
        }

        @Bean
        PushDispatcher pushDispatcher() {
            return org.mockito.Mockito.mock(PushDispatcher.class);
        }
    }
}
