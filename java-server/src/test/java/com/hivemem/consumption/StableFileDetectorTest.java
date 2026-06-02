package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StableFileDetectorTest {

    @Test
    void notStableOnFirstSighting() {
        StableFileDetector d = new StableFileDetector(5);
        Path p = Path.of("/tmp/a.pdf");
        assertFalse(d.isStable(p, 10L, 1000_000L, 1000_000L));
    }

    @Test
    void stableWhenUnchangedAndOldEnough() {
        StableFileDetector d = new StableFileDetector(5);
        Path p = Path.of("/tmp/a.pdf");
        d.isStable(p, 10L, 1000_000L, 1000_000L);
        assertTrue(d.isStable(p, 10L, 1000_000L, 1010_000L));
    }

    @Test
    void notStableWhenSizeChanged() {
        StableFileDetector d = new StableFileDetector(5);
        Path p = Path.of("/tmp/a.pdf");
        d.isStable(p, 10L, 1000_000L, 1000_000L);
        assertFalse(d.isStable(p, 20L, 1000_000L, 1010_000L));
    }

    @Test
    void notStableWhenTooFresh() {
        StableFileDetector d = new StableFileDetector(5);
        Path p = Path.of("/tmp/a.pdf");
        d.isStable(p, 10L, 1000_000L, 1000_000L);
        assertFalse(d.isStable(p, 10L, 1000_000L, 1002_000L));
    }

    @Test
    void forgetDropsState() {
        StableFileDetector d = new StableFileDetector(5);
        Path p = Path.of("/tmp/a.pdf");
        d.isStable(p, 10L, 1000_000L, 1000_000L);
        d.forget(p);
        assertFalse(d.isStable(p, 10L, 1000_000L, 1010_000L));
    }
}
