package com.hivemem.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks exactly one resolver. Fail-closed: Access mode without team-domain/audience
 * aborts startup rather than silently authenticating nobody — there must be no state
 * in which Access believes it is on but nothing is verified.
 */
@Configuration(proxyBeanMethods = false)
public class HumanAuthResolverConfig {

    @Bean
    HumanPrincipalResolver humanPrincipalResolver(AccessProperties props, TokenService tokenService) {
        if (!props.isEnabled()) {
            return new SessionResolver(tokenService);
        }
        if (props.getTeamDomain() == null || props.getTeamDomain().isBlank()
                || props.getAudience() == null || props.getAudience().isBlank()) {
            throw new IllegalStateException(
                    "hivemem.access.enabled=true requires hivemem.access.team-domain and "
                            + "hivemem.access.audience to be set");
        }
        return new AccessJwtResolver(props, tokenService);
    }
}
