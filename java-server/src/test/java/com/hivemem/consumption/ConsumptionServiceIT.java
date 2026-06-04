package com.hivemem.consumption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionServiceIT extends ConsumptionITSupport {

    @Test
    void consumesPlainTextFileAsCommittedCell(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("note.txt"), "Rechnung Acme GmbH Betrag 42 EUR");

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");
        ConsumptionService svc = buildService(cp);

        svc.processStaged(file);

        assertFalse(Files.exists(file), "source file should be moved away");
        assertTrue(Files.exists(root.resolve("processed").resolve("note.txt")),
                "file should land in processed/");

        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT realm, status, source FROM cells ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected at least one cell row");
            assertEquals("documents", rs.getString("realm"));
            assertEquals("committed", rs.getString("status"));
            assertTrue(rs.getString("source").startsWith("consumption:"),
                    "source should start with 'consumption:' but was: " + rs.getString("source"));
        }
    }
}
