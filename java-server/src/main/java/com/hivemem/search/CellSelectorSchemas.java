package com.hivemem.search;

import com.hivemem.mcp.ToolInputSchema;

/**
 * Shared nested input-schema builder for the {@code where} selector used by
 * {@code list_cell_ids} (read) and the selector-based variants of
 * {@code bulk_tag}/{@code bulk_reclassify} (write). Kept in {@code com.hivemem.search}
 * alongside {@link CellSelector} so both {@code tools.read} and {@code tools.write}
 * handlers can depend on it without a package-visibility hack.
 */
public final class CellSelectorSchemas {

    private CellSelectorSchemas() {
    }

    public static ToolInputSchema where() {
        return ToolInputSchema.object()
                .optionalString("realm", "Exact realm, or \"none\" for cells without a realm")
                .optionalStringList("realm_in", "Match any of these realms; may include \"none\"")
                .optionalEnumString("signal", "Signal filter", "facts", "events", "discoveries", "preferences", "advice")
                .optionalString("topic", "Exact topic")
                .optionalStringList("tags", "Cells having ANY of these tags")
                .optionalString("query", "Full-text filter (tsv, simple dictionary)")
                .optionalEnumString("status", "Status filter (default committed)", "committed", "pending", "rejected");
    }
}
