package com.hivemem.write;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for item B of the consumption-async-ingest design: reviseCell must carry the
 * cell↔attachment link to the new revision, so a scanned document whose cell is later revised by OCR
 * stays linked to its original PDF instead of the link being stranded on the superseded version.
 */
@Testcontainers
class ReviseCellAttachmentLinkIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM attachments");
        dsl.execute("DELETE FROM cells");
    }

    @Test
    void reviseCellCarriesAttachmentLinkToNewRevision() throws Exception {
        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, size_bytes, "
                    + "s3_key_original, uploaded_by) VALUES ('" + attId + "', 'hash1', 'application/pdf', "
                    + "'scan.pdf', 100, 'hash1.pdf', 'tester')");
            st.execute("INSERT INTO cells (id, content, realm, signal, status, created_at, valid_from) "
                    + "VALUES ('" + cellId + "', 'scan.pdf', 'documents', 'facts', 'committed', now(), now())");
            st.execute("INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source, created_at) "
                    + "VALUES ('" + cellId + "', '" + attId + "', true, now())");
        }

        Map<String, Object> result =
                new WriteToolRepository(dsl).reviseCell(cellId, "OCR'd text content", null, null, "tester", "committed");
        UUID newId = UUID.fromString(result.get("new_id").toString());

        // old cell superseded
        var oldValidUntil = dsl.fetchOne("SELECT valid_until FROM cells WHERE id = ?", cellId);
        assertNotNull(oldValidUntil.get("valid_until"), "old cell should be superseded (valid_until set)");

        // new revision is current and linked to the same attachment
        Integer currentLinks = dsl.fetchOne(
                "SELECT count(*) AS n FROM cell_attachments ca JOIN cells c ON c.id = ca.cell_id "
                        + "WHERE c.id = ? AND c.valid_until IS NULL AND ca.attachment_id = ?",
                newId, attId).get("n", Integer.class);
        assertEquals(1, currentLinks, "new (current) revision must keep the attachment link");
    }
}
