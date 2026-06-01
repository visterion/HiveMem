package com.hivemem.web;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionAuthFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    public SessionAuthFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/login")) return true;
        // OAuth discovery + registration + token are public; the /oauth/authorize
        // endpoint handles its own login redirect when the user is unauthenticated.
        if (path.startsWith("/.well-known/oauth-")) return true;
        if (path.startsWith("/oauth/")) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // If a principal was already injected (e.g. by a test harness), pass through immediately.
        if (request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean isMcp = path.startsWith("/mcp");
        boolean isHooks = path.startsWith("/hooks");
        boolean isApi = path.startsWith("/api/");
        boolean isVistierie = path.startsWith("/vistierie");

        HttpSession session = request.getSession(false);
        if (session != null) {
            String token = (String) session.getAttribute(LoginController.SESSION_TOKEN_KEY);
            if (token != null) {
                Optional<AuthPrincipal> principal = tokenService.validateToken(token);
                if (principal.isPresent()) {
                    request.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, principal.get());
                    filterChain.doFilter(request, response);
                    return;
                }
                session.invalidate();
            }
        }

        if (isMcp || isHooks || isVistierie) {
            filterChain.doFilter(request, response);
        } else if (isApi) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login");
        }
    }
}
