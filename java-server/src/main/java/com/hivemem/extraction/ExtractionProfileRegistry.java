package com.hivemem.extraction;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ExtractionProfileRegistry {

    private static final ExtractionProfile SYNTHETIC_OTHER = new ExtractionProfile(
            "other",
            "Du analysierst ein Dokument unbekannten Typs. Identifiziere Hauptthema und Schlüsselbegriffe. "
                    + "Wenn ein Ausstellungs-/Briefdatum erkennbar ist, gib es als document_date aus.",
            java.util.List.of("topic"),
            java.util.List.of("document_date", "key_term", "sentiment"),
            null,
            java.util.List.of()
    );

    private final Map<String, ExtractionProfile> profiles;

    public ExtractionProfileRegistry() {
        this("extraction-profiles/");
    }

    ExtractionProfileRegistry(String basePath) {
        Map<String, ExtractionProfile> loaded =
                new java.util.LinkedHashMap<>(ExtractionProfileLoader.loadFromClasspath(basePath));
        loaded.putIfAbsent("other", SYNTHETIC_OTHER);
        this.profiles = Map.copyOf(loaded);
    }

    public ExtractionProfile resolve(String type) {
        if (type == null) return fallback();
        ExtractionProfile p = profiles.get(type);
        return p != null ? p : fallback();
    }

    public ExtractionProfile fallback() {
        return profiles.get("other");
    }

    public ExtractionProfile resolveImageSubType(String subType) {
        String key = switch (subType == null ? "" : subType) {
            case "whiteboard_photo" -> "image-whiteboard";
            case "document_scan"    -> "image-document-scan";
            default                  -> "image-photo-general";
        };
        ExtractionProfile p = profiles.get(key);
        if (p == null) {
            // image-photo-general is required; fall back to "other" only if YAMLs are
            // misconfigured at deploy time
            return fallback();
        }
        return p;
    }

    public boolean isKnown(String type) {
        return type != null && profiles.containsKey(type);
    }

    public Set<String> knownTypes() {
        return profiles.keySet();
    }
}
