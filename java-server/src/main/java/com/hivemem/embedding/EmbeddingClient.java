package com.hivemem.embedding;

import java.util.List;

public interface EmbeddingClient {

    /** Hard cap derived from MiniLM token limit (~128 tokens ≈ 500 chars multilingual). */
    int CONTENT_EMBED_MAX_CHARS = 500;

    List<Float> encodeDocument(String text);

    default List<Float> encodeQuery(String text) {
        return encodeDocument(text);
    }

    /**
     * Three-tier embedding for cells:
     * <ul>
     *   <li>summary present → embed the summary</li>
     *   <li>summary absent + content ≤ {@link #CONTENT_EMBED_MAX_CHARS} → embed the content</li>
     *   <li>summary absent + content too long → return {@code null}; caller is expected to
     *       tag the cell as {@code needs_summary} so a summarizer can fill it in</li>
     * </ul>
     */
    default List<Float> encodeForCell(String content, String summary) {
        if (summary != null && !summary.isBlank()) {
            return encodeDocument(summary);
        }
        if (content != null && content.length() <= CONTENT_EMBED_MAX_CHARS) {
            return encodeDocument(content);
        }
        return null;
    }

    EmbeddingInfo getInfo();

    /**
     * The embedding dimension. Implementations may serve this from a local cache (see
     * {@link HttpEmbeddingClient}) so per-request callers (search_kg, entity_overview, …)
     * don't pay an HTTP hop; the default resolves it via {@link #getInfo()}.
     */
    default int dimension() {
        return getInfo().dimension();
    }

    /** Drop any cached vectors/model info (e.g. after an embedding-model migration). */
    default void invalidateCaches() {
    }
}
