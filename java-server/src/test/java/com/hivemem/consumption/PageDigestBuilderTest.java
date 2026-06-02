package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PageDigestBuilderTest {

    private final PageDigestBuilder b = new PageDigestBuilder();

    @Test
    void blankWhenEmptyOrWhitespace() {
        PageDigest d = b.build(1, "   \n  ");
        assertTrue(d.blank());
        assertEquals(1, d.page());
    }

    @Test
    void detectsSeiteXvonY() {
        assertTrue(b.build(2, "... Seite 1 von 3 ...").hasPageMarker());
        assertTrue(b.build(2, "... Page 2 of 5 ...").hasPageMarker());
        assertFalse(b.build(2, "kein marker hier").hasPageMarker());
    }

    @Test
    void headTailTruncated() {
        String text = "A".repeat(500) + "ZZZ";
        PageDigest d = b.build(1, text);
        assertTrue(d.head().length() <= 300);
        assertTrue(d.tail().endsWith("ZZZ"));
        assertTrue(d.tail().length() <= 100);
        assertFalse(d.blank());
    }
}
