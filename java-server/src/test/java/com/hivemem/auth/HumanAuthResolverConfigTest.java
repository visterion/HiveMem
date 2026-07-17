package com.hivemem.auth;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class HumanAuthResolverConfigTest {

    @Test
    void accessModeWithoutConfigFailsFast() {
        AccessProperties props = new AccessProperties();
        props.setEnabled(true);

        assertThatThrownBy(() -> new HumanAuthResolverConfig()
                .humanPrincipalResolver(props, Mockito.mock(TokenService.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team-domain");
    }

    @Test
    void legacyModeUsesSessionResolver() {
        AccessProperties props = new AccessProperties();
        assertThat(new HumanAuthResolverConfig()
                .humanPrincipalResolver(props, Mockito.mock(TokenService.class)))
                .isInstanceOf(SessionResolver.class);
    }
}
