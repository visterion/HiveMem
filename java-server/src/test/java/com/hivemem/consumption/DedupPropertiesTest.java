package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DedupPropertiesTest {
    @Test
    void defaultsAreSensible() {
        DedupProperties p = new DedupProperties();
        assertTrue(p.isEnabled());
        assertEquals(0.92, p.getRecallThreshold(), 1e-9);
        assertEquals(0.85, p.getTextThreshold(), 1e-9);
        assertEquals(10, p.getCandidateK());
    }
}
