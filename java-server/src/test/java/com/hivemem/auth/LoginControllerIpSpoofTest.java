package com.hivemem.auth;

import com.hivemem.auth.support.FixedTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code forward-headers-strategy: framework}, Spring wraps the request so that
 * {@code getRemoteAddr()} returns the client-supplied X-Forwarded-For value. The login
 * rate limiter must bucket on the real TCP peer (unwrapped request) — otherwise an
 * attacker evades the 15-minute lockout by rotating XFF headers.
 */
class LoginControllerIpSpoofTest {

    /** Simulates Spring's ForwardedHeaderFilter: getRemoteAddr() returns the spoofed XFF value. */
    private static HttpServletRequest xffSpoofedRequest(String spoofedAddr, String realPeer) {
        MockHttpServletRequest underlying = new MockHttpServletRequest("POST", "/login");
        underlying.setRemoteAddr(realPeer);
        return new HttpServletRequestWrapper(underlying) {
            @Override
            public String getRemoteAddr() {
                return spoofedAddr;
            }
        };
    }

    private static LoginController controller(LoginRateLimiter limiter) {
        TokenService tokens = new FixedTokenService(t ->
                "valid-token".equals(t)
                        ? Optional.of(new AuthPrincipal("alice", AuthRole.READER))
                        : Optional.empty());
        return new LoginController(tokens, limiter);
    }

    @Test
    void lockoutBucketsOnTcpPeerNotSpoofableRemoteAddr() throws Exception {
        LoginController controller = controller(new LoginRateLimiter());

        // 5 failures with rotating spoofed getRemoteAddr() values, all from the same TCP peer.
        for (int i = 0; i < 5; i++) {
            controller.handleLogin("bad-token", null,
                    xffSpoofedRequest("10.9.9." + i, "203.0.113.7"), new MockHttpServletResponse());
        }

        // The real peer must now be locked out — even with the correct token and yet
        // another fresh spoofed address.
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object result = controller.handleLogin("valid-token", null,
                xffSpoofedRequest("10.9.9.99", "203.0.113.7"), response);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(result).isNull();
    }

    @Test
    void differentTcpPeerIsNotAffectedByLockout() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        LoginController controller = controller(limiter);

        for (int i = 0; i < 5; i++) {
            controller.handleLogin("bad-token", null,
                    xffSpoofedRequest("10.9.9." + i, "203.0.113.7"), new MockHttpServletResponse());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        Object result = controller.handleLogin("valid-token", null,
                xffSpoofedRequest("10.9.9.99", "198.51.100.3"), response);
        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(result).isNotNull();
    }
}
