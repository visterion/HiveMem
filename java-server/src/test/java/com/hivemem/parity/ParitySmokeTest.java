package com.hivemem.parity;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.mcp.ToolHandler;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the role/tool permission surface. The name "parity" is historical —
 * this suite originally guarded parity with a (now-removed) Python implementation; today
 * it verifies that the per-role allow-lists in {@link ToolPermissionService} stay in
 * sync with the actual set of registered {@link ToolHandler} beans on the classpath.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ParitySmokeTest.TestConfig.class)
class ParitySmokeTest {

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

    private final ToolPermissionService toolPermissionService = new ToolPermissionService();

    @Autowired
    private List<ToolHandler> registeredHandlers;

    @Test
    void adminPermissionSetContainsFullExpectedToolCount() {
        assertThat(toolPermissionService.allowedTools(AuthRole.ADMIN))
                .hasSize(37)
                .contains("search", "add_cell", "approve_pending",
                        "health", "reclassify_cell", "queen_runs", "queen_run_detail",
                        "upload_attachment", "list_attachments", "get_attachment_info",
                        "facet_count")
                .doesNotContain("hivemem_check_duplicate", "hivemem_check_contradiction",
                        "add_peer", "remove_peer", "list_peers");
    }

    @Test
    void writerPermissionSetContainsReadAndWriteToolsButNoAdminTools() {
        assertThat(toolPermissionService.allowedTools(AuthRole.WRITER))
                .hasSize(33)
                .contains("search", "add_cell", "revise_cell", "reclassify_cell",
                        "upload_attachment", "list_attachments", "get_attachment_info",
                        "facet_count")
                .doesNotContain("health", "approve_pending", "hivemem_check_duplicate",
                        "hivemem_check_contradiction");
    }

    /**
     * Cross-class invariant: every registered {@link ToolHandler} bean must appear
     * in some role's allow-list, otherwise the handler is dead code — invisible to
     * {@code tools/list} and rejected by {@code tools/call} for every caller.
     *
     * <p>This guards the kind of drift where a new ToolHandler is added but the
     * matching entry in {@link ToolPermissionService} is forgotten (a real bug
     * historically — the three attachment tools shipped this way).
     */
    @Test
    void everyRegisteredToolHandlerIsReachableViaSomeRole() {
        Set<String> reachable = toolPermissionService.allowedTools(AuthRole.ADMIN);
        List<String> orphaned = registeredHandlers.stream()
                .map(ToolHandler::name)
                .filter(name -> !reachable.contains(name))
                .collect(Collectors.toUnmodifiableList());

        assertThat(orphaned)
                .as("ToolHandler beans not exposed by any role — add them to "
                        + "ToolPermissionService.READ_TOOLS / WRITE_TOOLS / ADMIN_TOOLS")
                .isEmpty();
    }

    /**
     * The mirror invariant: every name in the role allow-lists must correspond to
     * an actually registered {@link ToolHandler}. Catches typos and leftover entries
     * after a tool is removed.
     */
    @Test
    void everyAllowListedNameHasARegisteredToolHandler() {
        Set<String> registeredNames = registeredHandlers.stream()
                .map(ToolHandler::name)
                .collect(Collectors.toUnmodifiableSet());

        Set<String> allowed = toolPermissionService.allowedTools(AuthRole.ADMIN);
        List<String> phantom = allowed.stream()
                .filter(name -> !registeredNames.contains(name))
                .collect(Collectors.toUnmodifiableList());

        assertThat(phantom)
                .as("Names in ToolPermissionService allow-lists with no matching "
                        + "@Component ToolHandler bean — typo or stale entry")
                .isEmpty();
    }
}
