package com.hivemem.tools.read;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strips attachment fields that are internal storage/audit details and must never reach
 * MCP clients: {@code s3_key_original}, {@code s3_key_thumbnail} (raw S3 object keys),
 * {@code file_hash} (dedup key, not user-facing), and {@code uploaded_by} (internal
 * principal id). Shared between {@code list_attachments} and {@code get_attachment_info}
 * so both tools apply the same whitelist (see B4/LOW fix).
 */
final class AttachmentFieldFilter {

    private AttachmentFieldFilter() {}

    static Map<String, Object> strip(Map<String, Object> row) {
        Map<String, Object> filtered = new LinkedHashMap<>(row);
        filtered.remove("s3_key_original");
        filtered.remove("s3_key_thumbnail");
        filtered.remove("file_hash");
        filtered.remove("uploaded_by");
        return filtered;
    }
}
