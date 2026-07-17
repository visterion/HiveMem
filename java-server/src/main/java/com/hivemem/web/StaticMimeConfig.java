package com.hivemem.web;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedded Tomcat's default MIME mappings do not include the {@code webmanifest}
 * extension, so PWA manifest files are served with {@code Content-Type:
 * application/octet-stream} instead of the correct {@code application/manifest+json}.
 * This customizer adds the missing mapping.
 */
@Configuration
public class StaticMimeConfig {

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webManifestMimeCustomizer() {
        return factory -> {
            MimeMappings mappings = new MimeMappings();
            mappings.add("webmanifest", "application/manifest+json");
            // Merge into the existing (default) mappings rather than replacing them.
            factory.addMimeMappings(mappings);
        };
    }
}
