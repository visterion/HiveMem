package com.hivemem.attachment;

import java.util.UUID;

/** Published after image ingest when EXIF carried GPS coordinates needing reverse-geocoding. */
public record GeocodeRequestedEvent(UUID attachmentId, double lat, double lon) {}
