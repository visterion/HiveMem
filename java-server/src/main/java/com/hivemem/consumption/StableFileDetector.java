package com.hivemem.consumption;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Decides whether a file has stopped changing. All times in epoch millis. Not thread-safe;
 *  call from the single-threaded watcher only. */
public class StableFileDetector {

    private final long stableMillis;
    private final Map<Path, long[]> seen = new HashMap<>(); // path -> [size, mtimeMillis]

    public StableFileDetector(int stableSeconds) {
        this.stableMillis = stableSeconds * 1000L;
    }

    /** @return true if size+mtime match the previous sighting and mtime is old enough. */
    public boolean isStable(Path path, long size, long mtimeMillis, long nowMillis) {
        long[] prev = seen.get(path);
        seen.put(path, new long[] {size, mtimeMillis});
        if (prev == null) {
            return false; // first sighting
        }
        boolean unchanged = prev[0] == size && prev[1] == mtimeMillis;
        boolean oldEnough = (nowMillis - mtimeMillis) >= stableMillis;
        return unchanged && oldEnough;
    }

    public void forget(Path path) {
        seen.remove(path);
    }
}
