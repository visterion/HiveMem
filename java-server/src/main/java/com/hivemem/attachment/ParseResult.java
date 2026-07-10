package com.hivemem.attachment;

public record ParseResult(String extractedText, byte[] thumbnail, String thumbnailMimeType,
                          boolean scanLikely, Integer pageCount) {

    public static ParseResult textOnly(String text) {
        return new ParseResult(text, null, null, false, null);
    }

    public static ParseResult withThumbnail(String text, byte[] thumbnail) {
        return new ParseResult(text, thumbnail, "image/jpeg", false, null);
    }

    public static ParseResult withThumbnailAndScan(String text, byte[] thumbnail, boolean scanLikely,
                                                   Integer pageCount) {
        return new ParseResult(text, thumbnail, "image/jpeg", scanLikely, pageCount);
    }

    public static ParseResult empty() {
        return new ParseResult(null, null, null, false, null);
    }

    public boolean hasThumbnail() {
        return thumbnail != null && thumbnail.length > 0;
    }
}
