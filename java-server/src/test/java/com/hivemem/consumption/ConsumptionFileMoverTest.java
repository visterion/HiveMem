package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumptionFileMoverTest {

    @Test
    void movesToProcessedSubdir(@TempDir Path root) throws Exception {
        Path src = Files.writeString(root.resolve("scan.pdf"), "x");
        ConsumptionFileMover mover = new ConsumptionFileMover(root);
        Path dest = mover.moveToProcessed(src);
        assertFalse(Files.exists(src));
        assertTrue(dest.startsWith(root.resolve("processed")));
        assertTrue(Files.exists(dest));
    }

    @Test
    void movesToFailedSubdir(@TempDir Path root) throws Exception {
        Path src = Files.writeString(root.resolve("scan.pdf"), "x");
        ConsumptionFileMover mover = new ConsumptionFileMover(root);
        Path dest = mover.moveToFailed(src);
        assertTrue(dest.startsWith(root.resolve("failed")));
        assertTrue(Files.exists(dest));
    }

    @Test
    void movesFromProcessingSubdirBackToRoot(@TempDir Path root) throws Exception {
        ConsumptionFileMover mover = new ConsumptionFileMover(root);
        Path processingDir = root.resolve("processing");
        Files.createDirectories(processingDir);
        Path src = Files.writeString(processingDir.resolve("stale.pdf"), "stale");
        Path dest = mover.moveToRoot(src);
        assertFalse(Files.exists(src), "file should no longer be in processing/");
        assertEquals(root, dest.getParent(), "file should land directly in root");
        assertTrue(Files.exists(dest), "file should exist in root");
    }

    @Test
    void neverOverwritesExistingDestination(@TempDir Path root) throws Exception {
        // A file already sitting under failed/ (e.g. an earlier failed scan) must never be
        // silently replaced by a same-named move (ATOMIC_MOVE = rename(2) would overwrite).
        ConsumptionFileMover mover = new ConsumptionFileMover(root);
        Path failedDir = root.resolve("failed");
        Files.createDirectories(failedDir);
        Files.writeString(failedDir.resolve("scan.pdf"), "original");

        Path src = Files.writeString(root.resolve("scan.pdf"), "new");
        Path dest = mover.moveToFailed(src);

        assertNotEquals("scan.pdf", dest.getFileName().toString(), "collision must be suffixed");
        assertEquals("original", Files.readString(failedDir.resolve("scan.pdf")),
                "pre-existing file must be untouched");
        assertEquals("new", Files.readString(dest));
    }

    @Test
    void suffixesOnCollision(@TempDir Path root) throws Exception {
        ConsumptionFileMover mover = new ConsumptionFileMover(root);
        Path a = Files.writeString(root.resolve("scan.pdf"), "a");
        Path destA = mover.moveToProcessed(a);
        Path b = Files.writeString(root.resolve("scan.pdf"), "b");
        Path destB = mover.moveToProcessed(b);
        assertNotEquals(destA.getFileName(), destB.getFileName());
        assertTrue(Files.exists(destA));
        assertTrue(Files.exists(destB));
    }
}
