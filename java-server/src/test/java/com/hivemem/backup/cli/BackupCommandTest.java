package com.hivemem.backup.cli;

import com.hivemem.backup.BackupRestoreService;
import com.hivemem.backup.BackupService;
import com.hivemem.backup.Manifest;
import com.hivemem.backup.RestoreMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupCommandTest {

    private BackupService exportSvc;
    private BackupRestoreService restoreSvc;
    private AtomicInteger exitCode;
    private BackupCommand cmd;

    @BeforeEach
    void setUp() {
        exportSvc = mock(BackupService.class);
        restoreSvc = mock(BackupRestoreService.class);
        exitCode = new AtomicInteger(Integer.MIN_VALUE);
        cmd = new BackupCommand(exportSvc, restoreSvc, exitCode::set);
    }

    @Test
    void noArgs_printsUsageAndExitsTwo() throws Exception {
        cmd.run(new DefaultApplicationArguments());
        assertEquals(2, exitCode.get());
        verify(exportSvc, never()).export(any());
        verify(restoreSvc, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void wrongFirstToken_exitsTwo() throws Exception {
        cmd.run(new DefaultApplicationArguments("frobnicate", "export"));
        assertEquals(2, exitCode.get());
    }

    @Test
    void unknownSubcommand_exitsTwo() throws Exception {
        cmd.run(new DefaultApplicationArguments("backup", "wibble"));
        assertEquals(2, exitCode.get());
    }

    @Test
    void exportWithoutOut_exitsOne() throws Exception {
        cmd.run(new DefaultApplicationArguments("backup", "export"));
        assertEquals(1, exitCode.get());
        verify(exportSvc, never()).export(any());
    }

    @Test
    void exportSuccess_callsServiceAndExitsZero(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("backup.tar.gz");
        Manifest manifest = sampleManifest();
        when(exportSvc.export(any())).thenReturn(manifest);

        cmd.run(new DefaultApplicationArguments("backup", "export", "--out=" + out));

        assertEquals(0, exitCode.get());
        ArgumentCaptor<OutputStream> osCap = ArgumentCaptor.forClass(OutputStream.class);
        verify(exportSvc).export(osCap.capture());
        assertTrue(Files.exists(out), "output file should be created");
    }

    @Test
    void exportCreatesParentDirectories(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("nested/dirs/backup.tar.gz");
        when(exportSvc.export(any())).thenReturn(sampleManifest());

        cmd.run(new DefaultApplicationArguments("backup", "export", "--out=" + out));

        assertEquals(0, exitCode.get());
        assertTrue(Files.isDirectory(out.getParent()));
    }

    @Test
    void exportServiceThrows_exitsOne(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("backup.tar.gz");
        when(exportSvc.export(any())).thenThrow(new RuntimeException("disk full"));

        cmd.run(new DefaultApplicationArguments("backup", "export", "--out=" + out));

        assertEquals(1, exitCode.get());
    }

    @Test
    void restoreWithoutIn_exitsOne() throws Exception {
        cmd.run(new DefaultApplicationArguments("backup", "restore", "--yes"));
        assertEquals(1, exitCode.get());
        verify(restoreSvc, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void restoreDefaultsToMoveMode(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});

        cmd.run(new DefaultApplicationArguments("backup", "restore", "--in=" + in, "--yes"));

        assertEquals(0, exitCode.get());
        verify(restoreSvc).restore(any(), eq(RestoreMode.MOVE), eq(false));
    }

    @Test
    void restoreWithCloneAndForce(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});

        cmd.run(new DefaultApplicationArguments(
                "backup", "restore", "--in=" + in, "--mode=clone", "--force", "--yes"));

        assertEquals(0, exitCode.get());
        verify(restoreSvc).restore(any(), eq(RestoreMode.CLONE), eq(true));
    }

    @Test
    void restoreWithForce_printsWipeWarningBeforeImporting(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});

        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(captured));
        try {
            cmd.run(new DefaultApplicationArguments(
                    "backup", "restore", "--in=" + in, "--mode=clone", "--force", "--yes"));
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(0, exitCode.get());
        String output = captured.toString();
        assertTrue(output.contains("--force"), "expected a --force warning, got: " + output);
        assertTrue(output.toLowerCase().contains("empt"),
                "expected the warning to mention the target being left empty, got: " + output);
    }

    @Test
    void restoreWithoutForce_printsNoWipeWarning(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});

        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(captured));
        try {
            cmd.run(new DefaultApplicationArguments("backup", "restore", "--in=" + in, "--yes"));
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(0, exitCode.get());
        assertTrue(!captured.toString().contains("TRUNCATED/EMPTIED"));
    }

    @Test
    void restoreInvalidMode_exitsOne(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});

        cmd.run(new DefaultApplicationArguments(
                "backup", "restore", "--in=" + in, "--mode=swap", "--yes"));

        assertEquals(1, exitCode.get());
        verify(restoreSvc, never()).restore(any(), any(), anyBoolean());
    }

    @Test
    void restoreServiceThrows_exitsOne(@TempDir Path tmp) throws Exception {
        Path in = Files.write(tmp.resolve("backup.tar.gz"), new byte[]{0});
        doThrow(new RuntimeException("manifest mismatch"))
                .when(restoreSvc).restore(any(), any(), anyBoolean());

        cmd.run(new DefaultApplicationArguments("backup", "restore", "--in=" + in, "--yes"));

        assertEquals(1, exitCode.get());
    }

    private static Manifest sampleManifest() {
        return new Manifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "test-build",
                UUID.randomUUID(),
                Instant.parse("2026-05-03T00:00:00Z"),
                "0028",
                new Manifest.Counts(0, 0, 0, 0),
                new Manifest.OpsLog(0, 0),
                new Manifest.SyncPeers(0),
                new Manifest.AppliedOps(0),
                new Manifest.Postgres("custom", "postgres.dump", 0),
                new Manifest.Attachments("test-bucket", 0, 0)
        );
    }
}
