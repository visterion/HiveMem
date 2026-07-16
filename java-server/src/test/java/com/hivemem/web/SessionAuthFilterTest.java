package com.hivemem.web;

import com.hivemem.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionAuthFilterTest {

  private final SessionAuthFilter filter = new SessionAuthFilter(mock(TokenService.class));

  private boolean skip(String uri) {
    return filter.shouldNotFilter(new MockHttpServletRequest("GET", uri));
  }

  @Test
  void pwaShellAssetsBypassAuthWithoutSession() {
    for (String p : List.of(
        "/manifest.webmanifest", "/sw.js",
        "/pwa-64x64.png", "/pwa-192x192.png", "/pwa-512x512.png",
        "/maskable-icon-512x512.png", "/apple-touch-icon-180x180.png")) {
      assertThat(skip(p)).as("PWA asset %s must skip the session filter", p).isTrue();
    }
  }

  @Test
  void apiStillFilteredWithoutSession() {
    assertThat(skip("/api/attachments")).isFalse();
    assertThat(skip("/api/status")).isFalse();
  }

  @Test
  void spaRootStillFiltered() {
    assertThat(skip("/")).isFalse();
    assertThat(skip("/hive")).isFalse();
  }
}
