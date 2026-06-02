package com.hivemem.consumption;

import java.util.regex.Pattern;

public class PageDigestBuilder {

    private static final int HEAD = 300;
    private static final int TAIL = 100;
    private static final Pattern PAGE_MARKER = Pattern.compile(
            "(seite|page)\\s+\\d+\\s+(von|of)\\s+\\d+", Pattern.CASE_INSENSITIVE);

    public PageDigest build(int page, String ocrText) {
        String text = ocrText == null ? "" : ocrText.strip();
        boolean blank = text.isEmpty() || text.replaceAll("\\s", "").length() < 3;
        String head = text.length() <= HEAD ? text : text.substring(0, HEAD);
        String tail = text.length() <= TAIL ? text : text.substring(text.length() - TAIL);
        boolean marker = PAGE_MARKER.matcher(text).find();
        return new PageDigest(page, head, tail, blank, marker);
    }
}
