package com.hivemem.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PostgresRestorer {

    private final String psqlPath;

    public PostgresRestorer(String psqlPath) {
        this.psqlPath = psqlPath;
    }

    public void restore(String jdbcUrl, String user, String password, InputStream sql)
            throws IOException, InterruptedException {
        PostgresDumper.JdbcParts j = PostgresDumper.JdbcParts.parse(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                psqlPath,
                "-v", "ON_ERROR_STOP=1",
                "--single-transaction",
                "-h", j.host(), "-p", String.valueOf(j.port()),
                "-U", user, "-d", j.database()
        );
        pb.environment().put("PGPASSWORD", password);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain psql's output on a separate thread WHILE we feed stdin: otherwise a chatty
        // restore fills the 64KB stdout pipe, psql blocks writing, and our stdin write blocks
        // too — a deadlock the exit-code check never reaches.
        ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try {
                p.getInputStream().transferTo(logBuffer);
            } catch (IOException ignored) {
                // process died; waitFor() below surfaces the failure
            }
        }, "psql-restore-drain");
        drainer.start();
        try {
            try (var stdin = p.getOutputStream()) {
                sql.transferTo(stdin);
            }
            drainer.join();
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("psql restore failed (exit " + code + "): "
                        + logBuffer.toString());
            }
        } finally {
            p.destroyForcibly();
        }
    }
}
