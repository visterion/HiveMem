package com.hivemem.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;

class StaticMimeConfigTest {

    @Test
    void addsWebmanifestMimeMapping() {
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> customizer =
                new StaticMimeConfig().webManifestMimeCustomizer();

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        customizer.customize(factory);

        MimeMappings mappings = factory.getSettings().getMimeMappings();
        assertThat(mappings.get("webmanifest")).isEqualTo("application/manifest+json");
        // Default mappings must still be present, e.g. plain html.
        assertThat(mappings.get("html")).isEqualTo("text/html");
    }
}
