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
        var docA = result.stream().filter(d -> d.pages().equals(java.util.List.of(1, 5))).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("committed", docA.status());
        var docB = result.stream().filter(d -> d.pages().equals(java.util.List.of(2))).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("pending", docB.status());        // 0.3 < 0.5
    }
}
