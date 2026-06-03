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

    private Path move(Path src, String subdir) throws IOException {
        Path targetDir = root.resolve(subdir);
        Files.createDirectories(targetDir);
        Path dest = targetDir.resolve(src.getFileName());
        while (Files.exists(dest)) {
            dest = targetDir.resolve(suffixed(src.getFileName().toString(), counter.incrementAndGet()));
        }
        Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        return dest;
    }

    private static String suffixed(String name, long n) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name + "-" + n;
        return name.substring(0, dot) + "-" + n + name.substring(dot);
    }
}
