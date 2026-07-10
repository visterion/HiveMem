package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

/** Moves processed scans out of the consumption root into processed/ or failed/.
 *  Collision-safe via a monotonic counter suffix (no wall-clock; deterministic for tests). */
public class ConsumptionFileMover {

    public static final String PROCESSED = "processed";
    public static final String FAILED = "failed";
    public static final String PROCESSING = "processing";

    private final Path root;
    private final AtomicLong counter = new AtomicLong();

    public ConsumptionFileMover(Path root) {
        this.root = root;
    }

    public Path moveToProcessed(Path src) throws IOException { return move(src, PROCESSED); }
    public Path moveToFailed(Path src) throws IOException { return move(src, FAILED); }
    public Path moveToProcessing(Path src) throws IOException { return move(src, PROCESSING); }

    public Path moveToRoot(Path src) throws IOException {
        Files.createDirectories(root);
        return moveNoReplace(src, root);
    }

    private Path move(Path src, String subdir) throws IOException {
        Path targetDir = root.resolve(subdir);
        Files.createDirectories(targetDir);
        return moveNoReplace(src, targetDir);
    }

    /** Claim the destination name atomically (createFile is create-new) before moving onto it.
     *  A bare exists()-check would be TOCTOU-racy: two movers could pick the same free name and
     *  ATOMIC_MOVE (rename(2)) silently replaces, losing one of the files. */
    private Path moveNoReplace(Path src, Path targetDir) throws IOException {
        String name = src.getFileName().toString();
        Path dest = targetDir.resolve(name);
        while (true) {
            try {
                Files.createFile(dest);   // atomic claim of the destination name
                break;
            } catch (FileAlreadyExistsException taken) {
                dest = targetDir.resolve(suffixed(name, counter.incrementAndGet()));
            }
        }
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveErr) {
            try { Files.deleteIfExists(dest); } catch (IOException ignored) { } // drop the placeholder
            throw moveErr;
        }
        return dest;
    }

    private static String suffixed(String name, long n) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name + "-" + n;
        return name.substring(0, dot) + "-" + n + name.substring(dot);
    }
}
