package com.hivemem.hooks;

import java.util.UUID;

public record SourceAttribution(
        UUID cellId,
        String realm,
        String topic,
        Integer year,
        String referenceTitle,
        String referenceUrl
) {}
