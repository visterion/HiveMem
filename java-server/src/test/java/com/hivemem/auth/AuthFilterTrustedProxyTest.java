package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production is reachable only through the Cloudflare Tunnel, so {@link AuthFilter#tcpPeerAddress}
 * is loopback for every external request — without keying on the tunnel-injected
 * {@code CF-Connecting-IP} header, all clients would share one rate-limit bucket and 5
 * failed auths from any one client would lock out everyone. See {@link SecurityProperties}.
 */
class AuthFilterTrustedProxyTest {

    private static final String CF_HEADER = "CF-Connecting-IP";

    private static SecurityProperties props(boolean trustedProxy) {
        SecurityProperties p = new SecurityProperties();
        p.setTrustedProxy(trustedProxy);
        return p;
    }

    private static AuthFilter filter(SecurityProperties securityProperties) {
        return new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(), Optional.empty(),
                securityProperties);
    }

    private static MockHttpServletRequest request(String cfConnectingIp, String tcpPeer) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setRemoteAddr(tcpPeer);
        if (cfConnectingIp != null) {
            req.addHeader(CF_HEADER, cfConnectingIp);
        }
        return req;
    }

    private static int invoke(AuthFilter filter, HttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void trustedProxyOn_keysOnCfConnectingIpHeader() {
        assertThat(AuthFilter.rateLimitKey(request("1.2.3.4", "127.0.0.1"), props(true)))
                .isEqualTo("1.2.3.4");
    }

    @Test
    void trustedProxyOff_ignoresCfConnectingIpHeaderAndUsesTcpPeer() {
        assertThat(AuthFilter.rateLimitKey(request("1.2.3.4", "127.0.0.1"), props(false)))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void trustedProxyOn_noCfHeader_fallsBackToTcpPeer() {
        assertThat(AuthFilter.rateLimitKey(request(null, "127.0.0.1"), props(true)))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void trustedProxyOn_twoDifferentCfIpsGetIndependentBuckets() throws Exception {
        AuthFilter filter = filter(props(true));

        // 5 failed (unauthenticated) requests from CF IP 1.2.3.4, all via the same TCP peer
        // (as they would be through a single Cloudflare Tunnel egress).
        for (int i = 0; i < 5; i++) {
            invoke(filter, request("1.2.3.4", "127.0.0.1"));
        }
        int lockedOutStatus = invoke(filter, request("1.2.3.4", "127.0.0.1"));
        assertThat(lockedOutStatus).isEqualTo(429);

        // A different CF-Connecting-IP through the same tunnel TCP peer must not be affected.
        int unaffectedStatus = invoke(filter, request("5.6.7.8", "127.0.0.1"));
        assertThat(unaffectedStatus).isEqualTo(401); // unauthenticated, but not rate-limited
    }

    @Test
    void trustedProxyOff_sharesBucketAcrossDifferentCfHeadersFromSameTcpPeer() throws Exception {
        AuthFilter filter = filter(props(false));

        // Trusted-proxy is off, so the CF header is ignored; both "different" CF IPs
        // resolve to the same TCP peer and must share one bucket.
        for (int i = 0; i < 5; i++) {
            invoke(filter, request("1.2.3." + i, "127.0.0.1"));
        }
        int lockedOutStatus = invoke(filter, request("9.9.9.9", "127.0.0.1"));
        assertThat(lockedOutStatus).isEqualTo(429);
    }
}
