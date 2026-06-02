package com.hivemem.consumption;

public interface SeparationApplier {
    /** Apply boundaries to the awaiting job: split, ingest sub-docs, move batch, mark job done.
     *  No-op (logs) if no awaiting job matches the correlationId. */
    void apply(SeparationResult result);
}
