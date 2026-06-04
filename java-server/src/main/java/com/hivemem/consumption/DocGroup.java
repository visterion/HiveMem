package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.List;

/** A document being assembled: stable id, a short descriptor (for carry-over context), its global
 *  1-based page numbers, and the running min confidence of the assignments that built it. */
public final class DocGroup {
    public final String id;
    public String descriptor;
    public final List<Integer> pages = new ArrayList<>();
    public double minConfidence = 1.0;
    public DocGroup(String id, String descriptor) { this.id = id; this.descriptor = descriptor; }
}
