package com.hivemem.sync;

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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OpReplayerIntegrationTest.TestConfig.class)
class OpReplayerIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
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
    @Autowired OpReplayer replayer;
    @Autowired SyncPeerRepository syncPeerRepository;

    ObjectMapper objectMapper = new ObjectMapper();
    UUID sourcePeer = UUID.randomUUID();

    @Test
    void addCellReplayInsertsWithOriginalId() {
        UUID cellId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("content", "hello from peer");
        payload.put("realm", "engineering");
        payload.put("signal", "facts");
        payload.put("topic", "test");
        payload.put("status", "committed");

        OpDto op = new OpDto(1L, UUID.randomUUID(), "add_cell", payload, OffsetDateTime.now());
        OpReplayer.ReplayResult result = replayer.replay(sourcePeer, op);

        assertThat(result).isEqualTo(OpReplayer.ReplayResult.REPLAYED);
        long count = dsl.fetchOne("SELECT count(*) AS c FROM cells WHERE id = ?", cellId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void replayIsIdempotentForSameOpId() {
        UUID cellId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("content", "hello");
        payload.put("realm", "eng");
        payload.put("signal", "facts");
        payload.put("topic", "t");
        payload.put("status", "committed");

        OpDto op = new OpDto(2L, opId, "add_cell", payload, OffsetDateTime.now());
        replayer.replay(sourcePeer, op);
        OpReplayer.ReplayResult second = replayer.replay(sourcePeer, op);
        assertThat(second).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void conflictingAddCellIsRecorded() {
        UUID cellId = UUID.randomUUID();
        UUID sourcePeer1 = UUID.randomUUID();
        UUID sourcePeer2 = UUID.randomUUID();
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();

        // First replay: inserts cell (simulates what peer1 sent)
        ObjectNode payload1 = objectMapper.createObjectNode();
        payload1.put("cell_id", cellId.toString());
        payload1.put("content", "from peer1");
        payload1.put("realm", "eng");
        payload1.put("signal", "facts");
        payload1.put("topic", "t");
        payload1.put("status", "committed");
        replayer.replay(sourcePeer1, new OpDto(1L, opId1, "add_cell", payload1, OffsetDateTime.now()));

        // Second replay: same cell UUID from different peer → conflict
        ObjectNode payload2 = objectMapper.createObjectNode();
        payload2.put("cell_id", cellId.toString());
        payload2.put("content", "from peer2");
        payload2.put("realm", "eng");
        payload2.put("signal", "facts");
        payload2.put("topic", "t");
        payload2.put("status", "committed");
        OpDto op2 = new OpDto(2L, opId2, "add_cell", payload2, OffsetDateTime.now());
        OpReplayer.ReplayResult result = replayer.replay(sourcePeer2, op2);

        assertThat(result).isEqualTo(OpReplayer.ReplayResult.CONFLICT);
        long conflicts = dsl.fetchOne("SELECT count(*) AS c FROM sync_conflicts WHERE cell_id = ?", cellId)
                .get("c", Long.class);
        assertThat(conflicts).isEqualTo(1L);
    }

    @Test
    void unknownOpTypeIsSkipped() {
        ObjectNode payload = objectMapper.createObjectNode();
        OpDto op = new OpDto(4L, UUID.randomUUID(), "unknown_future_op", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.UNKNOWN_OP);
    }

    @Test
    void reclassifyCellUpdatesClassification() {
        UUID cellId = insertMinimalCell();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("new_realm", "new-realm");
        payload.put("new_topic", "new-topic");
        payload.put("new_signal", "events");

        OpDto op = new OpDto(10L, UUID.randomUUID(), "reclassify_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT realm, topic, signal FROM cells WHERE id = ?", cellId);
        assertThat(row.get("realm", String.class)).isEqualTo("new-realm");
        assertThat(row.get("topic", String.class)).isEqualTo("new-topic");
        assertThat(row.get("signal", String.class)).isEqualTo("events");
    }

    @Test
    void updateIdentityUpsertsKey() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("key", "test-identity-key");
        payload.put("content", "some identity content");
        payload.put("token_count", 4);

        OpDto op = new OpDto(11L, UUID.randomUUID(), "update_identity", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String content = dsl.fetchOne("SELECT content FROM identity WHERE key = ?", "test-identity-key")
                .get("content", String.class);
        assertThat(content).isEqualTo("some identity content");

        // second replay with different content updates in-place
        payload.put("content", "updated content");
        OpDto op2 = new OpDto(12L, UUID.randomUUID(), "update_identity", payload, OffsetDateTime.now());
        replayer.replay(sourcePeer, op2);
        content = dsl.fetchOne("SELECT content FROM identity WHERE key = ?", "test-identity-key")
                .get("content", String.class);
        assertThat(content).isEqualTo("updated content");
    }

    @Test
    void addReferenceInsertsWithOriginalId() {
        UUID refId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reference_id", refId.toString());
        payload.put("title", "Test Reference");
        payload.put("url", "https://example.com");
        payload.put("ref_type", "article");
        payload.put("status", "read");

        OpDto op = new OpDto(13L, UUID.randomUUID(), "add_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne("SELECT count(*) AS c FROM references_ WHERE id = ?", refId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // duplicate is skipped
        OpDto op2 = new OpDto(14L, UUID.randomUUID(), "add_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void linkReferenceInsertsAndDeduplicates() {
        UUID cellId = insertMinimalCell();
        UUID refId = insertMinimalReference();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("reference_id", refId.toString());
        payload.put("relation", "source");

        OpDto op = new OpDto(15L, UUID.randomUUID(), "link_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne(
                "SELECT count(*) AS c FROM cell_references WHERE cell_id = ? AND reference_id = ?", cellId, refId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // replay with a different opId should be skipped (same link already exists)
        OpDto op2 = new OpDto(16L, UUID.randomUUID(), "link_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void diaryWriteInsertsEntry() {
        ensureAgent("diary-test-agent");
        UUID entryId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("entry_id", entryId.toString());
        payload.put("agent", "diary-test-agent");
        payload.put("entry", "Today I did something interesting.");

        OpDto op = new OpDto(17L, UUID.randomUUID(), "diary_write", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String entry = dsl.fetchOne("SELECT entry FROM agent_diary WHERE id = ?", entryId)
                .get("entry", String.class);
        assertThat(entry).isEqualTo("Today I did something interesting.");

        OpDto op2 = new OpDto(18L, UUID.randomUUID(), "diary_write", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }


    @Test
    void updateBlueprintInsertsNewVersion() {
        UUID blueprintId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("blueprint_id", blueprintId.toString());
        payload.put("realm", "sync-test-realm");
        payload.put("title", "Sync Test Blueprint");
        payload.put("narrative", "This is the narrative.");
        payload.put("agent_id", "test-agent");

        OpDto op = new OpDto(19L, UUID.randomUUID(), "update_blueprint", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne("SELECT count(*) AS c FROM blueprints WHERE id = ?", blueprintId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // duplicate blueprint_id skipped
        OpDto op2 = new OpDto(20L, UUID.randomUUID(), "update_blueprint", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void reviseCellArchivesOldAndInsertsNew() {
        UUID oldId = insertMinimalCell();
        UUID newId = UUID.randomUUID();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", oldId.toString());
        payload.put("new_cell_id", newId.toString());
        payload.put("new_content", "revised content");
        payload.put("new_summary", "revised summary");
        payload.put("status", "committed");

        OpDto op = new OpDto(30L, UUID.randomUUID(), "revise_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var oldRow = dsl.fetchOne("SELECT valid_until FROM cells WHERE id = ?", oldId);
        assertThat(oldRow.get("valid_until", OffsetDateTime.class)).isNotNull();

        var newRow = dsl.fetchOne("SELECT content, parent_id FROM cells WHERE id = ?", newId);
        assertThat(newRow.get("content", String.class)).isEqualTo("revised content");
        assertThat(newRow.get("parent_id", UUID.class)).isEqualTo(oldId);
    }

    @Test
    void reviseCellSkippedWhenNewIdMissing() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", UUID.randomUUID().toString());
        OpDto op = new OpDto(31L, UUID.randomUUID(), "revise_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void reviseCellSkippedWhenNewIdAlreadyExists() {
        UUID oldId = insertMinimalCell();
        UUID newId = insertMinimalCell();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", oldId.toString());
        payload.put("new_cell_id", newId.toString());
        payload.put("new_content", "x");
        payload.put("new_summary", "y");

        OpDto op = new OpDto(32L, UUID.randomUUID(), "revise_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void reviseCellSkippedWhenOldNotFound() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", UUID.randomUUID().toString());
        payload.put("new_cell_id", UUID.randomUUID().toString());
        payload.put("new_content", "x");
        payload.put("new_summary", "y");
        OpDto op = new OpDto(33L, UUID.randomUUID(), "revise_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void kgAddInsertsFact() {
        UUID factId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fact_id", factId.toString());
        payload.put("subject", "earth");
        payload.put("predicate", "is");
        payload.put("object", "round");
        payload.put("confidence", 0.95);
        payload.put("status", "committed");

        OpDto op = new OpDto(40L, UUID.randomUUID(), "kg_add", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT subject, predicate, \"object\" FROM facts WHERE id = ?", factId);
        assertThat(row.get("subject", String.class)).isEqualTo("earth");
        assertThat(row.get("predicate", String.class)).isEqualTo("is");
        assertThat(row.get("object", String.class)).isEqualTo("round");

        OpDto op2 = new OpDto(41L, UUID.randomUUID(), "kg_add", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void kgInvalidateClosesFact() {
        UUID factId = insertMinimalFact();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fact_id", factId.toString());
        OpDto op = new OpDto(42L, UUID.randomUUID(), "kg_invalidate", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var validUntil = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", factId)
                .get("valid_until", OffsetDateTime.class);
        assertThat(validUntil).isNotNull();
    }

    @Test
    void reviseFactArchivesOldAndInsertsNew() {
        UUID oldId = insertMinimalFact();
        UUID newId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fact_id", oldId.toString());
        payload.put("new_fact_id", newId.toString());
        payload.put("new_object", "new-object-value");
        payload.put("status", "committed");

        OpDto op = new OpDto(43L, UUID.randomUUID(), "revise_fact", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        assertThat(dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", oldId)
                .get("valid_until", OffsetDateTime.class)).isNotNull();
        assertThat(dsl.fetchOne("SELECT \"object\" FROM facts WHERE id = ?", newId)
                .get("object", String.class)).isEqualTo("new-object-value");
    }

    @Test
    void reviseFactSkippedWhenNewIdMissing() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fact_id", UUID.randomUUID().toString());
        OpDto op = new OpDto(44L, UUID.randomUUID(), "revise_fact", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void reviseFactSkippedWhenOldNotFound() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fact_id", UUID.randomUUID().toString());
        payload.put("new_fact_id", UUID.randomUUID().toString());
        payload.put("new_object", "x");
        OpDto op = new OpDto(45L, UUID.randomUUID(), "revise_fact", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void addTunnelInsertsAndDeduplicates() {
        UUID fromCell = insertMinimalCell();
        UUID toCell = insertMinimalCell();
        UUID tunnelId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tunnel_id", tunnelId.toString());
        payload.put("from_cell_id", fromCell.toString());
        payload.put("to_cell_id", toCell.toString());
        payload.put("relation", "related_to");
        payload.put("note", "test note");
        payload.put("status", "committed");
        payload.put("agent_id", "test-agent");

        OpDto op = new OpDto(50L, UUID.randomUUID(), "add_tunnel", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne("SELECT count(*) AS c FROM tunnels WHERE id = ?", tunnelId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        OpDto op2 = new OpDto(51L, UUID.randomUUID(), "add_tunnel", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void removeTunnelClosesTunnel() {
        UUID fromCell = insertMinimalCell();
        UUID toCell = insertMinimalCell();
        UUID tunnelId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, status, created_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'related_to', 'committed', 'test')
                """, tunnelId, fromCell, toCell);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tunnel_id", tunnelId.toString());
        OpDto op = new OpDto(52L, UUID.randomUUID(), "remove_tunnel", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var validUntil = dsl.fetchOne("SELECT valid_until FROM tunnels WHERE id = ?", tunnelId)
                .get("valid_until", OffsetDateTime.class);
        assertThat(validUntil).isNotNull();
    }

    @Test
    void registerAgentUpsertsAgent() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", "replayed-agent");
        payload.put("focus", "test focus");
        payload.put("schedule", "0 0 * * *");
        payload.putObject("autonomy").put("default", "auto_apply");
        payload.putObject("model_routing").put("default", "claude-opus");
        payload.putArray("tools").add("search").add("write");

        OpDto op = new OpDto(60L, UUID.randomUUID(), "register_agent", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT focus, schedule FROM agents WHERE name = ?", "replayed-agent");
        assertThat(row.get("focus", String.class)).isEqualTo("test focus");
        assertThat(row.get("schedule", String.class)).isEqualTo("0 0 * * *");

        // upsert: change focus
        payload.put("focus", "updated focus");
        OpDto op2 = new OpDto(61L, UUID.randomUUID(), "register_agent", payload, OffsetDateTime.now());
        replayer.replay(sourcePeer, op2);
        assertThat(dsl.fetchOne("SELECT focus FROM agents WHERE name = ?", "replayed-agent")
                .get("focus", String.class)).isEqualTo("updated focus");
    }

    @Test
    void approvePendingAlsoUpdatesFactsAndTunnels() {
        UUID factId = insertPendingFact();
        UUID fromCell = insertMinimalCell();
        UUID toCell = insertMinimalCell();
        UUID tunnelId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, status, created_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'related_to', 'pending', 'test')
                """, tunnelId, fromCell, toCell);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("decision", "committed");
        payload.putArray("ids").add(factId.toString()).add(tunnelId.toString());
        OpDto op = new OpDto(70L, UUID.randomUUID(), "approve_pending", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        assertThat(dsl.fetchOne("SELECT status FROM facts WHERE id = ?", factId)
                .get("status", String.class)).isEqualTo("committed");
        assertThat(dsl.fetchOne("SELECT status FROM tunnels WHERE id = ?", tunnelId)
                .get("status", String.class)).isEqualTo("committed");
    }

    @Test
    void replayAllReturnsCountsAndHandlesNullInputs() {
        assertThat(replayer.replayAll(null, java.util.List.of()).replayed()).isEqualTo(0);
        assertThat(replayer.replayAll(sourcePeer, null).replayed()).isEqualTo(0);

        UUID cellId = UUID.randomUUID();
        ObjectNode addPayload = objectMapper.createObjectNode();
        addPayload.put("cell_id", cellId.toString());
        addPayload.put("content", "batch");
        addPayload.put("realm", "eng");
        addPayload.put("signal", "facts");
        addPayload.put("topic", "t");
        addPayload.put("status", "committed");

        ObjectNode unknownPayload = objectMapper.createObjectNode();

        var batch = java.util.List.of(
                new OpDto(80L, UUID.randomUUID(), "add_cell", addPayload, OffsetDateTime.now()),
                new OpDto(81L, UUID.randomUUID(), "unknown_op", unknownPayload, OffsetDateTime.now()));

        OpReplayer.BatchResult result = replayer.replayAll(sourcePeer, batch);
        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void replayReturnsFailedWhenExecutionThrows() {
        // Malformed UUID in cell_id triggers IllegalArgumentException → FAILED (not SKIPPED):
        // callers must not advance last_seen_seq past it.
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", "not-a-uuid");
        payload.put("content", "x");
        OpDto op = new OpDto(90L, UUID.randomUUID(), "add_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.FAILED);
    }

    @Test
    void addCellReplayHandlesNullEmbeddingForLongContentWithoutSummary() {
        // encodeForCell returns null for content > 500 chars without a summary; replay must
        // insert with NULL embedding + needs_summary instead of NPEing (and losing the op).
        UUID cellId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("content", "x".repeat(600));
        payload.put("realm", "eng");
        payload.put("signal", "facts");
        payload.put("topic", "t");
        payload.put("status", "committed");

        OpDto op = new OpDto(91L, UUID.randomUUID(), "add_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT embedding IS NULL AS no_emb, tags FROM cells WHERE id = ?", cellId);
        assertThat(row.get("no_emb", Boolean.class)).isTrue();
        assertThat(row.get("tags", String[].class)).contains("needs_summary");
    }

    @Test
    void rejectCellReplaySetsStatusRejected() {
        UUID cellId = insertMinimalCell();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("reason", "wrong info");

        OpDto op = new OpDto(92L, UUID.randomUUID(), "reject_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);
        assertThat(dsl.fetchOne("SELECT status FROM cells WHERE id = ?", cellId)
                .get("status", String.class)).isEqualTo("rejected");

        // idempotent: rejecting an already-rejected cell still replays cleanly
        OpDto op2 = new OpDto(93L, UUID.randomUUID(), "reject_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);
    }

    @Test
    void addTagsReplayUnionsTags() {
        UUID cellId = insertMinimalCell();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.putArray("tags").add("alpha").add("beta");

        OpDto op = new OpDto(94L, UUID.randomUUID(), "add_tags", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        assertThat(dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", cellId)
                .get("tags", String[].class)).contains("alpha", "beta");
    }

    @Test
    void removeTagsReplayRemovesTags() {
        UUID cellId = insertMinimalCell();
        dsl.execute("UPDATE cells SET tags = ARRAY['alpha','beta'] WHERE id = ?", cellId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.putArray("tags").add("alpha");

        OpDto op = new OpDto(95L, UUID.randomUUID(), "remove_tags", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String[] tags = dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", cellId)
                .get("tags", String[].class);
        assertThat(tags).containsExactly("beta");
    }

    @Test
    void bulkTagReplayAppliesAddAndRemoveToAllCells() {
        UUID cellA = insertMinimalCell();
        UUID cellB = insertMinimalCell();
        dsl.execute("UPDATE cells SET tags = ARRAY['old'] WHERE id IN (?, ?)", cellA, cellB);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("cell_ids").add(cellA.toString()).add(cellB.toString());
        payload.putArray("add_tags").add("fresh");
        payload.putArray("remove_tags").add("old");

        OpDto op = new OpDto(96L, UUID.randomUUID(), "bulk_tag", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        for (UUID id : new UUID[] {cellA, cellB}) {
            String[] tags = dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", id)
                    .get("tags", String[].class);
            assertThat(tags).contains("fresh").doesNotContain("old");
        }
    }

    @Test
    void updateCellMetaReplayUpdatesFieldsAndTags() {
        UUID cellId = insertMinimalCell();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("document_type", "invoice");
        payload.put("topic", "Stadtwerke Rechnung 2026");
        payload.put("valid_from", "2026-01-15T00:00:00Z");
        payload.putArray("add_tags").add("steuerrelevant").add("tax_scanned");

        OpDto op = new OpDto(97L, UUID.randomUUID(), "update_cell_meta", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT document_type, topic, valid_from, tags FROM cells WHERE id = ?", cellId);
        assertThat(row.get("document_type", String.class)).isEqualTo("invoice");
        assertThat(row.get("topic", String.class)).isEqualTo("Stadtwerke Rechnung 2026");
        assertThat(row.get("valid_from", OffsetDateTime.class).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-01-15T00:00:00Z").toInstant());
        assertThat(row.get("tags", String[].class)).contains("steuerrelevant", "tax_scanned");
    }

    @Test
    void reviseCellReplayPrefersPayloadMetadataOverOldRevision() {
        UUID oldId = insertMinimalCell();
        dsl.execute("UPDATE cells SET tags = ARRAY['keep-me'], insight = 'old insight', "
                + "key_points = ARRAY['old kp'] WHERE id = ?", oldId);
        UUID newId = UUID.randomUUID();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", oldId.toString());
        payload.put("new_cell_id", newId.toString());
        payload.put("new_content", "revised content");
        payload.put("new_summary", "revised summary");
        payload.put("new_insight", "llm insight");
        payload.putArray("new_key_points").add("llm kp1").add("llm kp2");
        payload.putArray("new_tags").add("llm-tag");
        payload.put("status", "committed");

        OpDto op = new OpDto(98L, UUID.randomUUID(), "revise_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT insight, key_points, tags FROM cells WHERE id = ?", newId);
        assertThat(row.get("insight", String.class)).isEqualTo("llm insight");
        assertThat(row.get("key_points", String[].class)).containsExactly("llm kp1", "llm kp2");
        // new_tags are merged (union) with the old revision's tags
        assertThat(row.get("tags", String[].class)).contains("keep-me", "llm-tag");
    }

    @Test
    void approvePendingTransitionsStatus() {
        UUID cellId = insertPendingCell();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("decision", "committed");
        payload.putArray("ids").add(cellId.toString());

        OpDto op = new OpDto(21L, UUID.randomUUID(), "approve_pending", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String status = dsl.fetchOne("SELECT status FROM cells WHERE id = ?", cellId)
                .get("status", String.class);
        assertThat(status).isEqualTo("committed");
    }

    // --- helpers ---

    private UUID insertMinimalCell() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status)
                VALUES (?::uuid, 'test', array_fill(0, ARRAY[1024])::vector, 'test-realm', 'facts', 'test', 'committed')
                """, cellId);
        return cellId;
    }

    private UUID insertPendingCell() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status)
                VALUES (?::uuid, 'pending-test', array_fill(0, ARRAY[1024])::vector, 'test-realm', 'facts', 'test', 'pending')
                """, cellId);
        return cellId;
    }

    private void ensureAgent(String name) {
        dsl.execute("""
                INSERT INTO agents (name, focus) VALUES (?, 'test focus')
                ON CONFLICT (name) DO NOTHING
                """, name);
    }

    private UUID insertMinimalFact() {
        UUID factId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", confidence, status)
                VALUES (?::uuid, 's', 'p', 'o', 1.0, 'committed')
                """, factId);
        return factId;
    }

    private UUID insertPendingFact() {
        UUID factId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", confidence, status)
                VALUES (?::uuid, 's', 'p', 'o', 1.0, 'pending')
                """, factId);
        return factId;
    }

    private UUID insertMinimalReference() {
        UUID refId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO references_ (id, title, status)
                VALUES (?::uuid, 'Test Ref', 'read')
                """, refId);
        return refId;
    }
}
