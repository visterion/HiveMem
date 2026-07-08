package com.hivemem.kg;

import java.util.Locale;

/** Pure subject normalization for alias lookup: trim, lowercase, collapse internal whitespace. */
public final class KgEntityNormalizer {

    private KgEntityNormalizer() {
    }

    public static String normalize(String subject) {
        if (subject == null) {
            return null;
        }
        return subject.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
