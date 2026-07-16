package com.hivemem.admin;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.sync.InstanceConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice test for {@link AdminController#createToken} carrying the per-token realm ACL
 * (read_realms/write_realms) through to {@link TokenService#createToken}, and round-tripping
 * through {@link TokenService#validateToken} the way an operator's CLI flow would.
 */
class AdminControllerTokenTest {

    private TokenService tokenService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        InstanceConfig instanceConfig = mock(InstanceConfig.class);
        tokenService = mock(TokenService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.hivemem.summarize.SummarizerService> summarizer =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        controller = new AdminController(instanceConfig, tokenService,
                mock(com.hivemem.attachment.AttachmentChunkRepairService.class), summarizer,
                mock(com.hivemem.consumption.DocumentDedupService.class));
    }

    @Test
    void createToken_withRealms_passesThemThroughAndRoundTripsViaValidateToken() {
        List<String> readRealms = List.of("dracul-research", "dracul");
        List<String> writeRealms = List.of("dracul-research");
        when(tokenService.createToken(eq("dracul-research-agent"), eq(AuthRole.WRITER), isNull(),
                eq(readRealms), eq(writeRealms)))
                .thenReturn("plaintext-token");

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("dracul-research-agent", "writer", null,
                        readRealms, writeRealms),
                adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("plaintext-token", body.get("token"));
        verify(tokenService).createToken("dracul-research-agent", AuthRole.WRITER, null, readRealms, writeRealms);

        AuthPrincipal scoped = new AuthPrincipal("dracul-research-agent", AuthRole.WRITER,
                UUID.randomUUID(), readRealms, writeRealms);
        when(tokenService.validateToken("plaintext-token")).thenReturn(java.util.Optional.of(scoped));

        AuthPrincipal principal = tokenService.validateToken("plaintext-token").orElseThrow();
        assertEquals(readRealms, principal.readRealms());
        assertEquals(writeRealms, principal.writeRealms());
    }

    @Test
    void createToken_unscoped_passesNullRealms_regression() {
        when(tokenService.createToken(eq("plain-svc"), eq(AuthRole.WRITER), isNull(), isNull(), isNull()))
                .thenReturn("tok");

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("plain-svc", "writer", null, null, null),
                adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(tokenService).createToken("plain-svc", AuthRole.WRITER, null, null, null);

        AuthPrincipal unscoped = new AuthPrincipal("plain-svc", AuthRole.WRITER, UUID.randomUUID(), null, null);
        assertNull(unscoped.readRealms());
        assertNull(unscoped.writeRealms());
    }

    private static HttpServletRequest adminRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin-test", AuthRole.ADMIN));
        return r;
    }
}
