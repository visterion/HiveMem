package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Optional;

/** Legacy mode: the human proved identity by pasting an API token into /login. */
public class SessionResolver implements HumanPrincipalResolver {

    private final TokenService tokenService;

    public SessionResolver(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Optional<AuthPrincipal> resolve(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object token = session.getAttribute(LoginController.SESSION_TOKEN_KEY);
        if (!(token instanceof String t)) return Optional.empty();
        Optional<AuthPrincipal> principal = tokenService.validateToken(t);
        if (principal.isEmpty()) session.invalidate();
        return principal;
    }
}
