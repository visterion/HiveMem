package com.hivemem.consumption;

/** Compact per-page representation sent to the separator LLM. page is 1-based. */
public record PageDigest(int page, String head, String tail, boolean blank, boolean hasPageMarker) {}
