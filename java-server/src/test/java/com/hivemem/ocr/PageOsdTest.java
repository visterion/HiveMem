package com.hivemem.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PageOsdTest {

    @Test
    void parsesRotate180() {
        String osd = "Page number: 0\nOrientation in degrees: 180\nRotate: 180\nOrientation confidence: 12.3\n"
                + "Script: Latin\nScript confidence: 5.0\n";
        assertEquals(180, PageOsd.parseRotate(osd));
    }

    @Test
    void parsesRotate0() {
        assertEquals(0, PageOsd.parseRotate("Rotate: 0\n"));
    }

    @Test
    void missingRotateReturnsZero() {
        assertEquals(0, PageOsd.parseRotate("Orientation in degrees: 0\n"));
    }

    @Test
    void garbageReturnsZero() {
        assertEquals(0, PageOsd.parseRotate(""));
        assertEquals(0, PageOsd.parseRotate(null));
    }

    @Test
    void nonMultipleOfNinetyNormalizesToZero() {
        assertEquals(0, PageOsd.parseRotate("Rotate: 47\n"));
    }
}
