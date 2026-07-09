package com.hivemem.consumption;

import org.junit.jupiter.api.Test;

class PageReassemblerTest {

    @Test
    void ordersPagesAndAppliesConfidenceStatus() {
        var props = new ConsumptionProperties();
        props.setReassemblyConfidenceThreshold(0.5);
        var a = new DocGroup("A", "invoice"); a.pages.addAll(java.util.List.of(5, 1)); a.minConfidence = 0.9;
        var b = new DocGroup("B", "letter");  b.pages.add(2);                          b.minConfidence = 0.3;
        var result = new PageReassembler(props).toDocuments(java.util.List.of(a, b), 5);
        // page 3,4 never assigned -> two extra single-page pending docs
        org.junit.jupiter.api.Assertions.assertEquals(4, result.size());
        var docA = result.stream().filter(d -> d.pages().equals(java.util.List.of(5, 1))).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("committed", docA.status());
        var docB = result.stream().filter(d -> d.pages().equals(java.util.List.of(2))).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("pending", docB.status());        // 0.3 < 0.5
    }

    private static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, id);
        for (int p : pages) g.pages.add(p);
        g.minConfidence = confidence;
        return g;
    }

    @Test
    void groupPageOrderIsPreserved() {
        PageReassembler r = new PageReassembler(new ConsumptionProperties());
        var docs =
                r.toDocuments(java.util.List.of(group("vf", 0.9, 16, 15, 14, 12)), 16);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(16, 15, 14, 12), docs.get(0).pages());
    }

    @Test
    void duplicatesAreDroppedKeepingFirstOccurrence() {
        PageReassembler r = new PageReassembler(new ConsumptionProperties());
        var docs =
                r.toDocuments(java.util.List.of(group("a", 0.9, 3, 1, 3, 2)), 3);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(3, 1, 2), docs.get(0).pages());
    }
}
