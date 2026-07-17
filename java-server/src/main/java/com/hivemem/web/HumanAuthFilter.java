package com.hivemem.web;

import com.hivemem.auth.AccessProperties;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.HumanPrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates humans — browsers presenting either a Cloudflare Access JWT or a
 * legacy session cookie, depending on deployment mode (see {@link HumanPrincipalResolver}
 * / {@link AccessProperties}). Machine callers ({@code /mcp}, {@code /hooks}, {@code
 * /sync}, {@code /vistierie}, bearer-authed {@code /admin}) are explicitly none of this
 * filter's business: it passes them straight through to {@link AuthFilter} without
 * resolving a human principal at all. That early passthrough is what makes a browser
 * session unable to authenticate {@code /mcp} — the entire point of the human/machine
 * auth split.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HumanAuthFilter extends OncePerRequestFilter {

    private final HumanPrincipalResolver humanPrincipalResolver;
    private final AccessProperties accessProperties;

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
    private static final Set<String> PWA_PUBLIC_ASSETS = Set.of(
            "/manifest.webmanifest",
            "/sw.js",
            "/pwa-64x64.png",
            "/pwa-192x192.png",
            "/pwa-512x512.png",
            "/maskable-icon-512x512.png",
            "/apple-touch-icon-180x180.png");

    public HumanAuthFilter(HumanPrincipalResolver humanPrincipalResolver, AccessProperties accessProperties) {
        this.humanPrincipalResolver = humanPrincipalResolver;
        this.accessProperties = accessProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Legacy only: in Access mode /login does not exist and must not be exempt.
        if (!accessProperties.isEnabled() && path.startsWith("/login")) return true;
        // The SPA must learn its auth mode before it can authenticate at all.
        if (path.equals("/api/config")) return true;
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

        // Machine paths are none of this filter's business — they authenticate with a
        // bearer token in AuthFilter. Not resolving a human principal here is what makes
        // /mcp reject session cookies.
        if (isMcp || isHooks || isVistierie || isSync || isAdminBearer) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthPrincipal> principal = humanPrincipalResolver.resolve(request);
        if (principal.isPresent()) {
            // Mirror of AuthFilter:162-165, which only guards the bearer path: a
            // realm-scoped principal may only use /api/tools/call, where ToolCallDispatcher
            // enforces the ACL. /api/gui/stream and /api/attachments have no realm filter
            // at all, so anything else is denied.
            if (principal.get().isRealmScoped() && !path.equals("/api/tools/call")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            request.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, principal.get());
            filterChain.doFilter(request, response);
            return;
        }

        if (isApi) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else if (accessProperties.isEnabled()) {
            // No /login exists in Access mode — a redirect would 404 or fall through to
            // the SPA shell. Happens on direct-origin access that bypasses the tunnel.
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        } else {
            response.sendRedirect(request.getContextPath() + "/login");
        }
    }
}
