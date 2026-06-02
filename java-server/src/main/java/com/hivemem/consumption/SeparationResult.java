package com.hivemem.consumption;

import java.util.List;
import java.util.UUID;

/** Webhook payload Vistierie POSTs to /vistierie/separation/done. */
public record SeparationResult(UUID correlationId, List<Boundary> boundaries) {
    /** Cut AFTER this 1-based page, with the model's confidence in this boundary. */
    public record Boundary(int afterPage, double confidence) {}
}
