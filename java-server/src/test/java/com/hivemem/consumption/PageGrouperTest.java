package com.hivemem.consumption;

import org.junit.jupiter.api.Test;

class PageGrouperTest {

    // helper: a BlockImage with global page number n + a 1x1 png; the mock ignores the image bytes
    private static PageGrouper.BlockImage img(int n) {
        return new PageGrouper.BlockImage(n, new VisionMultiClient.Image("image/png", "x"));
    }

    @Test
    void carriesGroupsAcrossBlocks() {
        var vm = org.mockito.Mockito.mock(VisionMultiClient.class);
        org.mockito.Mockito.when(vm.group(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("[{\"page\":1,\"docId\":\"A\",\"isNew\":true,\"descriptor\":\"inv\",\"confidence\":0.9}]")
            .thenReturn("[{\"page\":2,\"docId\":\"A\",\"isNew\":false,\"confidence\":0.8}]");
        var grouper = new PageGrouper(vm, new ConsumptionProperties());
        var groups = new java.util.ArrayList<DocGroup>();
        grouper.groupBlock("documents", groups, java.util.List.of(img(1)));   // block 1 (page 1)
        grouper.groupBlock("documents", groups, java.util.List.of(img(2)));   // block 2 (page 2)
        org.junit.jupiter.api.Assertions.assertEquals(1, groups.size());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(1, 2), new java.util.ArrayList<>(new java.util.TreeSet<>(groups.get(0).pages)));
        org.junit.jupiter.api.Assertions.assertEquals(0.8, groups.get(0).minConfidence, 1e-9);
    }
}
