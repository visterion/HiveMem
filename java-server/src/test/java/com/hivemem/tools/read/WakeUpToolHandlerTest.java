package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WakeUpToolHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void includesConfiguredDefaultLanguage() {
        ReadToolService readToolService = mock(ReadToolService.class);
        when(readToolService.wakeUp()).thenReturn(Map.of("recent", java.util.List.of()));
        WakeUpToolHandler handler = new WakeUpToolHandler(readToolService, "en");

        AuthPrincipal principal = new AuthPrincipal("alice", AuthRole.ADMIN);
        Map<String, Object> result = (Map<String, Object>) handler.call(principal, null);

        assertThat(result.get("default_language")).isEqualTo("en");
        assertThat(result.get("identity")).isEqualTo("alice");
    }
}
