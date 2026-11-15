package com.hivemem.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A current cell row whose valid_from was replaced by a revision and can be restored. */
public record EventTimeCandidate(UUID cellId, String status,
        OffsetDateTime oldValidFrom, OffsetDateTime newValidFrom) {}
