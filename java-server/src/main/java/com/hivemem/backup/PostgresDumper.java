package com.hivemem.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class PostgresDumper {

    private final String pgDumpPath;

    public PostgresDumper(String pgDumpPath) {
        this.pgDumpPath = pgDumpPath;
    }

    public void dump(String jdbcUrl, String user, String password, OutputStream out)
            throws IOException, InterruptedException {
        JdbcParts j = JdbcParts.parse(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                pgDumpPath,
                "--format=plain",
                "--data-only",
                "--serializable-deferrable",
                "--exclude-table=flyway_schema_history",
                "--exclude-table=migration_baseline",
                "-h", j.host(), "-p", String.valueOf(j.port()),
                "-U", user, "-d", j.database()
        );
        pb.environment().put("PGPASSWORD", password);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // Drain stderr on a separate thread WHILE we read stdout: a verbose pg_dump can fill
        // the 64KB stderr pipe and block, which in turn stalls the stdout read forever.
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        Thread errDrainer = new Thread(() -> {
            try {
                p.getErrorStream().transferTo(errBuffer);
            } catch (IOException ignored) {
                // process died; waitFor() below surfaces the failure
            }
        }, "pg-dump-stderr-drain");
        errDrainer.start();
        try {
            try (var in = p.getInputStream()) {
                in.transferTo(out);
            }
            errDrainer.join();
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("pg_dump failed (exit " + code + "): " + errBuffer.toString());
            }
        } finally {
            p.destroyForcibly();
        }
    }

    record JdbcParts(String host, int port, String database) {
        static JdbcParts parse(String jdbcUrl) {
            // Format: jdbc:postgresql://host:port/db?params
            String s = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
            int slash = s.indexOf('/');
            String hostPort = s.substring(0, slash);
            String rest = s.substring(slash + 1);
            int q = rest.indexOf('?');
            String db = (q >= 0) ? rest.substring(0, q) : rest;
            int colon = hostPort.indexOf(':');
            String host = (colon >= 0) ? hostPort.substring(0, colon) : hostPort;
            int port = (colon >= 0) ? Integer.parseInt(hostPort.substring(colon + 1)) : 5432;
            return new JdbcParts(host, port, db);
        }
    }
}
