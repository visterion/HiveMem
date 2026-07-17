package com.hivemem.web;

import com.hivemem.auth.AccessProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Unauthenticated: the SPA must know which auth mode it runs in before it can decide
 * how to re-authenticate. Exposes the mode and nothing else — no team domain, no
 * audience, no version.
 */
@RestController
public class ConfigController {

    private final AccessProperties accessProperties;

    public ConfigController(AccessProperties accessProperties) {
        this.accessProperties = accessProperties;
    }

    @GetMapping("/api/config")
    public Map<String, String> config() {
        return Map.of("authMode", accessProperties.isEnabled() ? "access" : "legacy");
    }
}
