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

    /**
     * Public PWA shell assets. The browser fetches the manifest (via
     * {@code <link rel="manifest">}) and the periodic service-worker script update
     * without credentials, so these must bypass the session gate — otherwise the
     * sessionless request is 302'd to /login and the install/SW breaks. Exact match
     * only (getRequestURI() is unnormalized; never prefix-match a filename list).
     * These are non-sensitive static bytes (the app shell is public OSS anyway);
     * user data stays behind /api and /mcp. Names mirror the @vite-pwa/assets-generator
     * "minimal-2023" preset output — reconcile with the real dist/ output in Task 2.
     */
    private static final java.util.Set<String> PWA_PUBLIC_ASSETS = java.util.Set.of(
            "/manifest.webmanifest",
            "/sw.js",
            "/pwa-64x64.png",
            "/pwa-192x192.png",
            "/pwa-512x512.png",
            "/maskable-icon-512x512.png",
            "/apple-touch-icon-180x180.png");

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
        if (PWA_PUBLIC_ASSETS.contains(path)) return true;
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
        // Peer sync authenticates with a bearer token (no browser session); defer to
        // AuthFilter like /mcp and /hooks instead of redirecting the peer to /login.
        boolean isSync = path.startsWith("/sync");
        // /admin serves two callers: browsers (session cookie; sessionless requests are
        // redirected to /login below) and CLI/scripts presenting a bearer token
        // (e.g. connect-peers.sh -> /admin/peers). Bearer requests defer to AuthFilter,
        // which validates the token or 401s — never an unauthenticated passthrough.
        boolean isAdminBearer = path.startsWith("/admin")
                && request.getHeader("Authorization") != null;

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

        if (isMcp || isHooks || isVistierie || isSync || isAdminBearer) {
            filterChain.doFilter(request, response);
        } else if (isApi) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login");
        }
    }
}
