package com.hivemem.sync;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WriteHandlerOpLogIntegrationTest.TestConfig.class)
class WriteHandlerOpLogIntegrationTest {

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

    @Autowired DSLContext dsl;
    @Autowired WriteToolService service;

    private AuthPrincipal admin() { return new AuthPrincipal("admin", AuthRole.ADMIN); }

    private long opCount(String opType) {
        return dsl.fetchOne("SELECT count(*) AS c FROM ops_log WHERE op_type = ?", opType)
                .get("c", Long.class);
    }

    private String latestPayload(String opType) {
        return dsl.fetchOne("SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = ? ORDER BY seq DESC LIMIT 1", opType)
                .get("p", String.class);
    }

    @Test
    void addCellEmitsAddCellOp() {
        long before = opCount("add_cell");
        Map<String, Object> result = service.addCell(
                admin(), "hello world", "engineering", "facts", "test",
                null, List.of(), 1, "summary", List.of(), null, null, null, null, null);

        assertThat(opCount("add_cell")).isEqualTo(before + 1);
        String payload = latestPayload("add_cell");
        assertThat(payload).contains((String) result.get("id"));
        assertThat(payload).contains("\"engineering\"");
        assertThat(payload).contains("\"facts\"");
        assertThat(payload).contains("\"hello world\"");
    }

    @Test
    void addCellPayloadContainsSourceTagsAndDedupeThreshold() {
        service.addCell(
                admin(), "indexed content", "engineering", "facts", "testing",
                "https://example.com/ref", List.of("tag-a", "tag-b"), 3,
                null, null, null, null, null, null, 0.85);

        String payload = latestPayload("add_cell");
        assertThat(payload).contains("\"https://example.com/ref\"");
        assertThat(payload).contains("\"tag-a\"");
        assertThat(payload).contains("\"tag-b\"");
        assertThat(payload).contains("0.85");
    }

    @Test
    void reviseCellEmitsReviseCellOp() {
        Map<String, Object> created = service.addCell(
                admin(), "original", "engineering", "facts", "topic",
                null, List.of(), 1, "summary", List.of(), null, null, null, null, null);
        UUID cellId = UUID.fromString((String) created.get("id"));

        long before = opCount("revise_cell");
        service.reviseCell(admin(), cellId, "revised content", "revised summary");

        assertThat(opCount("revise_cell")).isEqualTo(before + 1);
        String payload = latestPayload("revise_cell");
        assertThat(payload).contains(cellId.toString());
        assertThat(payload).contains("\"revised content\"");
        assertThat(payload).contains("\"revised summary\"");
    }

    @Test
    void reclassifyCellEmitsReclassifyOp() {
        Map<String, Object> created = service.addCell(
                admin(), "x", "engineering", "facts", "topic",
                null, List.of(), 1, "s", List.of(), null, null, null, null, null);
        UUID cellId = UUID.fromString((String) created.get("id"));

        long before = opCount("reclassify_cell");
        service.reclassifyCell(admin(), cellId, "personal", "newtopic", "events", null);

        assertThat(opCount("reclassify_cell")).isEqualTo(before + 1);
        String payload = latestPayload("reclassify_cell");
        assertThat(payload).contains(cellId.toString());
        assertThat(payload).contains("\"personal\"").contains("\"newtopic\"").contains("\"events\"");
    }

    @Test
    void addTunnelEmitsAddTunnelOp() {
        UUID a = UUID.fromString((String) service.addCell(
                admin(), "a", "engineering", "facts", "t", null, List.of(), 1, "s", List.of(), null, null, null, null, null).get("id"));
        UUID b = UUID.fromString((String) service.addCell(
                admin(), "b", "engineering", "facts", "t", null, List.of(), 1, "s", List.of(), null, null, null, null, null).get("id"));

        long before = opCount("add_tunnel");
        service.addTunnel(admin(), a, b, "related_to", "note", null);

        assertThat(opCount("add_tunnel")).isEqualTo(before + 1);
        String payload = latestPayload("add_tunnel");
        assertThat(payload).contains(a.toString()).contains(b.toString())
                .contains("\"related_to\"").contains("\"note\"");
    }

    @Test
    void kgAddEmitsKgAddOp() {
        long before = opCount("kg_add");
        service.kgAdd(admin(), "subject", "predicate", "object", 1.0, null, null, null, "insert");

        assertThat(opCount("kg_add")).isEqualTo(before + 1);
        String payload = latestPayload("kg_add");
        assertThat(payload).contains("\"subject\"").contains("\"predicate\"").contains("\"object\"");
    }

    @Test
    void removeTunnelEmitsRemoveTunnelOp() {
        UUID a = UUID.fromString((String) service.addCell(
                admin(), "a", "engineering", "facts", "t", null, List.of(), 1, "s", List.of(), null, null, null, null, null).get("id"));
        UUID b = UUID.fromString((String) service.addCell(
                admin(), "b", "engineering", "facts", "t", null, List.of(), 1, "s", List.of(), null, null, null, null, null).get("id"));
        UUID tunnelId = UUID.fromString((String) service.addTunnel(admin(), a, b, "related_to", null, null).get("id"));

        long before = opCount("remove_tunnel");
        service.removeTunnel(tunnelId);

        assertThat(opCount("remove_tunnel")).isEqualTo(before + 1);
        assertThat(latestPayload("remove_tunnel")).contains(tunnelId.toString());
    }

    @Test
    void kgInvalidateEmitsOp() {
        Map<String, Object> added = service.kgAdd(admin(), "s", "p", "o", 1.0, null, null, null, "insert");
        UUID factId = UUID.fromString((String) added.get("id"));

        long before = opCount("kg_invalidate");
        service.kgInvalidate(factId);

        assertThat(opCount("kg_invalidate")).isEqualTo(before + 1);
        assertThat(latestPayload("kg_invalidate")).contains(factId.toString());
    }

    @Test
    void reviseFactEmitsOp() {
        Map<String, Object> added = service.kgAdd(admin(), "s2", "p2", "o2", 1.0, null, null, null, "insert");
        UUID oldId = UUID.fromString((String) added.get("id"));

        long before = opCount("revise_fact");
        service.reviseFact(admin(), oldId, "newObject");

        assertThat(opCount("revise_fact")).isEqualTo(before + 1);
        String payload = latestPayload("revise_fact");
        assertThat(payload).contains(oldId.toString()).contains("\"newObject\"");
    }

    @Test
    void addReferenceEmitsOp() {
        long before = opCount("add_reference");
        service.addReference("title", "https://example.com", "author", "article", null, "notes", List.of("tag"), 1);

        assertThat(opCount("add_reference")).isEqualTo(before + 1);
        String payload = latestPayload("add_reference");
        assertThat(payload).contains("\"title\"").contains("\"https://example.com\"");
    }

    @Test
    void linkReferenceEmitsOp() {
        UUID cellId = UUID.fromString((String) service.addCell(
                admin(), "c", "engineering", "facts", "t", null, List.of(), 1, "s", List.of(), null, null, null, null, null).get("id"));
        UUID refId = UUID.fromString((String) service.addReference(
                "t", "https://x", null, null, null, null, List.of(), 1).get("id"));

        long before = opCount("link_reference");
        service.linkReference(cellId, refId, "source");

        assertThat(opCount("link_reference")).isEqualTo(before + 1);
        String payload = latestPayload("link_reference");
        assertThat(payload).contains(cellId.toString()).contains(refId.toString()).contains("\"source\"");
    }

    @Test
    void registerAgentEmitsOp() {
        long before = opCount("register_agent");
        service.registerAgent("agent-x", "focus", "{}", "schedule", "{}", List.of("tool1"));

        assertThat(opCount("register_agent")).isEqualTo(before + 1);
        String payload = latestPayload("register_agent");
        assertThat(payload).contains("\"agent-x\"").contains("\"focus\"");
    }

    @Test
    void updateIdentityEmitsOp() {
        long before = opCount("update_identity");
        service.updateIdentity("role", "I am a tester");

        assertThat(opCount("update_identity")).isEqualTo(before + 1);
        String payload = latestPayload("update_identity");
        assertThat(payload).contains("\"role\"").contains("\"I am a tester\"");
    }

    @Test
    void updateBlueprintEmitsOp() {
        long before = opCount("update_blueprint");
        service.updateBlueprint(admin(), "engineering", "Title", "narrative", List.of("facts"), List.of());

        assertThat(opCount("update_blueprint")).isEqualTo(before + 1);
        String payload = latestPayload("update_blueprint");
        assertThat(payload).contains("\"engineering\"").contains("\"Title\"").contains("\"narrative\"");
    }

    @Test
    void diaryWriteEmitsOp() {
        service.registerAgent("agent-x", "focus", "{}", null, "{}", List.of());
        long before = opCount("diary_write");
        service.diaryWrite("agent-x", "today I learned");

        assertThat(opCount("diary_write")).isEqualTo(before + 1);
        String payload = latestPayload("diary_write");
        assertThat(payload).contains("\"agent-x\"").contains("\"today I learned\"");
    }

    @Test
    void reviseCellOpIncludesNewCellId() {
        Map<String, Object> added = service.addCell(admin(), "original content",
                "engineering", "facts", "test", null, List.of(), 1, "summary", List.of(), null, null, null, null, null);
        UUID oldId = UUID.fromString(added.get("id").toString());

        long seqBefore = dsl.fetchOne("SELECT max(seq) AS s FROM ops_log").get("s", Long.class);
        service.reviseCell(admin(), oldId, "revised content", "revised summary");

        var op = dsl.fetchOne(
                "SELECT payload FROM ops_log WHERE seq > ? AND op_type = 'revise_cell' ORDER BY seq DESC LIMIT 1",
                seqBefore);
        assertThat(op).isNotNull();
        String payload = op.get("payload", org.jooq.JSONB.class).data();
        assertThat(payload).contains("new_cell_id");
    }

    @Test
    void reviseFact_OpIncludesNewFactId() {
        Map<String, Object> added = service.kgAdd(admin(), "S", "P", "O", 1.0, null, null, null, null);
        UUID oldId = UUID.fromString(added.get("id").toString());

        long seqBefore = dsl.fetchOne("SELECT max(seq) AS s FROM ops_log").get("s", Long.class);
        service.reviseFact(admin(), oldId, "O2");

        var op = dsl.fetchOne(
                "SELECT payload FROM ops_log WHERE seq > ? AND op_type = 'revise_fact' ORDER BY seq DESC LIMIT 1",
                seqBefore);
        assertThat(op).isNotNull();
        String payload = op.get("payload", org.jooq.JSONB.class).data();
        assertThat(payload).contains("new_fact_id");
    }

    @Test
    void approvePendingEmitsOp() {
        AuthPrincipal agent = new AuthPrincipal("agent-x", AuthRole.AGENT);
        Map<String, Object> added = service.kgAdd(agent, "s3", "p3", "o3", 1.0, null, null, null, "insert");
        UUID pendingId = UUID.fromString((String) added.get("id"));

        long before = opCount("approve_pending");
        // "approve" violates facts_status_check; use "committed" (the valid approval status)
        service.approvePending(List.of(pendingId), "committed");

        assertThat(opCount("approve_pending")).isEqualTo(before + 1);
        String payload = latestPayload("approve_pending");
        assertThat(payload).contains(pendingId.toString()).contains("\"committed\"");
    }

    @Test
    void cellInsertAndOpLogRollbackTogetherOnFailure() {
        long opsBefore = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        long cellsBefore = dsl.fetchOne("SELECT count(*) AS c FROM cells").get("c", Long.class);

        // Trigger a constraint violation by passing an invalid signal value.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                service.addCell(admin(), "x", "engineering", "INVALID_SIGNAL", "t",
                        null, java.util.List.of(), 1, "s", java.util.List.of(),
                        null, null, null, null, null));

        long opsAfter = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        long cellsAfter = dsl.fetchOne("SELECT count(*) AS c FROM cells").get("c", Long.class);
        assertThat(opsAfter).isEqualTo(opsBefore);
        assertThat(cellsAfter).isEqualTo(cellsBefore);
    }
}
