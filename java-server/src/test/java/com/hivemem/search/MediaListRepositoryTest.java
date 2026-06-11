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
        classes = MediaListRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MediaListRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({MediaListRepository.class, TestConfig.class})
    static class TestApplication {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
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
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired MediaListRepository repo;
    @Autowired DSLContext dsl;

    static final UUID C_PHOTO   = UUID.fromString("00000000-0000-0000-0034-000000000001");
    static final UUID C_WHITE   = UUID.fromString("00000000-0000-0000-0034-000000000002");
    static final UUID C_SCAN    = UUID.fromString("00000000-0000-0000-0034-000000000003");
    static final UUID C_OTHER   = UUID.fromString("00000000-0000-0000-0034-000000000004");
    static final UUID A_PHOTO   = UUID.fromString("00000000-0000-0000-0034-000000000011");
    static final UUID A_WHITE   = UUID.fromString("00000000-0000-0000-0034-000000000012");
    static final UUID A_SCAN    = UUID.fromString("00000000-0000-0000-0034-000000000013");
    static final UUID A_OTHER   = UUID.fromString("00000000-0000-0000-0034-000000000014");

    private void seedImage(UUID cell, UUID att, String realm, String subtypeTag,
                           String createdAt, String takenAt) {
        dsl.execute("INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, 'img', ?, 'events', 'media', ?, 'committed', now(), ?::timestamptz)",
                cell, realm, new String[]{subtypeTag}, createdAt);
        dsl.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, uploaded_by, created_at) VALUES " +
                "(?, ?, 'image/jpeg', 'p.jpg', 100, 'orig/p.jpg', 'thumb/p.jpg', 'system', ?::timestamptz)",
                att, "hash-" + att, createdAt);
        dsl.execute("INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source) " +
                "VALUES (?, ?, true)", cell, att);
        if (takenAt != null) {
            dsl.execute("INSERT INTO attachment_image_meta (attachment_id, width, height, taken_at, " +
                    "camera_make, camera_model, gps_lat, gps_lon, place_name, geocode_status) VALUES " +
                    "(?, 4032, 3024, ?::timestamptz, 'Apple', 'iPhone 16 Pro', 49.4874, 8.4660, 'Mannheim, DE', 'done')",
                    att, takenAt);
        }
    }

    @BeforeEach
    void seed() {
        dsl.execute("DELETE FROM cell_attachments WHERE cell_id IN (?, ?, ?, ?)", C_PHOTO, C_WHITE, C_SCAN, C_OTHER);
        dsl.execute("DELETE FROM attachment_image_meta WHERE attachment_id IN (?, ?, ?, ?)", A_PHOTO, A_WHITE, A_SCAN, A_OTHER);
        dsl.execute("DELETE FROM attachments WHERE id IN (?, ?, ?, ?)", A_PHOTO, A_WHITE, A_SCAN, A_OTHER);
        dsl.execute("DELETE FROM cells WHERE realm IN ('mphotos','otherrealm')");
        seedImage(C_PHOTO, A_PHOTO, "mphotos", "subtype_photo_general", "2026-06-01T00:00:00Z", "2026-05-10T00:00:00Z");
        seedImage(C_WHITE, A_WHITE, "mphotos", "subtype_whiteboard_photo", "2026-06-05T00:00:00Z", null);
        seedImage(C_SCAN, A_SCAN, "mphotos", "subtype_document_scan", "2026-06-02T00:00:00Z", "2026-05-20T00:00:00Z");
        seedImage(C_OTHER, A_OTHER, "otherrealm", "subtype_photo_general", "2026-06-03T00:00:00Z", "2026-05-15T00:00:00Z");
    }

    @Test
    void listsOnlyPhotoAndWhiteboardAcrossAllRealms() {
        List<Map<String, Object>> rows = repo.listMedia(null, "newest", 50, 0);
        List<String> cellIds = rows.stream().map(r -> (String) r.get("cell_id")).toList();
        assertThat(cellIds).contains(C_PHOTO.toString(), C_WHITE.toString(), C_OTHER.toString());
        assertThat(cellIds).doesNotContain(C_SCAN.toString());
    }

    @Test
    void realmFilterRestrictsToOneRealm() {
        List<Map<String, Object>> rows = repo.listMedia("otherrealm", "newest", 50, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("cell_id")).isEqualTo(C_OTHER.toString());
        assertThat(rows.get(0).get("place_name")).isEqualTo("Mannheim, DE");
        assertThat(rows.get(0).get("width")).isEqualTo(4032);
    }

    @Test
    void newestSortsByTakenAtFallingBackToCreatedAt() {
        List<Map<String, Object>> rows = repo.listMedia("mphotos", "newest", 50, 0);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("cell_id")).isEqualTo(C_WHITE.toString());
        assertThat(rows.get(1).get("cell_id")).isEqualTo(C_PHOTO.toString());
    }

    @Test
    void oldestSortsByTakenAtAscFallingBackToCreatedAt() {
        List<Map<String, Object>> rows = repo.listMedia("mphotos", "oldest", 50, 0);
        assertThat(rows).hasSize(2);
        // photo (taken_at 2026-05-10) is older than whiteboard (no taken_at → created_at 2026-06-05)
        assertThat(rows.get(0).get("cell_id")).isEqualTo(C_PHOTO.toString());
        assertThat(rows.get(1).get("cell_id")).isEqualTo(C_WHITE.toString());
    }

    @Test
    void pagingLimitOffset() {
        List<Map<String, Object>> rows = repo.listMedia("mphotos", "newest", 1, 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("cell_id")).isEqualTo(C_PHOTO.toString());
    }

    @Test
    void rowExposesUriPattern() {
        List<Map<String, Object>> rows = repo.listMedia("otherrealm", "newest", 50, 0);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("thumbnail_uri")).isEqualTo("hivemem://attachments/" + A_OTHER + "/thumbnail");
        assertThat(row.get("content_uri")).isEqualTo("hivemem://attachments/" + A_OTHER + "/content");
    }
}
