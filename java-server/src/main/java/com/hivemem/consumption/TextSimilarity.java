package com.hivemem.consumption;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Normalized-text similarity used as the precision gate for scan dedup. */
public final class TextSimilarity {

    private static final Pattern PAGE_MARKER = Pattern.compile("\\[page=\\d+\\]");
    // Keep letters (incl. German umlauts) and digits; everything else becomes a space.
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final int NGRAM = 4;

    private TextSimilarity() {}

    /** Lowercase, strip [page=N] markers, collapse to single-spaced word tokens. */
    static String normalize(String text) {
        if (text == null) return "";
        String s = PAGE_MARKER.matcher(text).replaceAll(" ");
        s = s.toLowerCase();
        s = NON_WORD.matcher(s).replaceAll(" ");
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Character {@value #NGRAM}-grams of the normalized text (robust to single-char OCR errors).
     *  Text at most NGRAM chars long yields one shingle (the whole string). */
    static Set<String> shingles(String normalized) {
        Set<String> out = new HashSet<>();
        if (normalized.isEmpty()) return out;
        if (normalized.length() <= NGRAM) {
            out.add(normalized);
            return out;
        }
        for (int i = 0; i + NGRAM <= normalized.length(); i++) {
            out.add(normalized.substring(i, i + NGRAM));
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** Similarity in [0,1]. Blank/marker-only text never matches (returns 0.0). */
    public static double similarity(String a, String b) {
        return jaccard(shingles(normalize(a)), shingles(normalize(b)));
    }
}
