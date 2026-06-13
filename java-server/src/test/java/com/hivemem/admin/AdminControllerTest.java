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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AdminControllerTest {

    private InstanceConfig instanceConfig;
    private TokenService tokenService;
    private AdminController controller;
    private static final UUID INSTANCE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        instanceConfig = mock(InstanceConfig.class);
        tokenService = mock(TokenService.class);
        when(instanceConfig.instanceId()).thenReturn(INSTANCE_ID);
        controller = new AdminController(instanceConfig, tokenService,
                mock(com.hivemem.attachment.AttachmentChunkRepairService.class));
    }

    // ── identity ───────────────────────────────────────────────────────────

    @Test
    void identity_returnsInstanceUuidForAdmin() {
        ResponseEntity<?> resp = controller.identity(adminRequest());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(INSTANCE_ID.toString(), ((Map<?,?>) resp.getBody()).get("instance_uuid"));
    }

    @Test
    void identity_forbiddenForWriter() {
        ResponseEntity<?> resp = controller.identity(writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(instanceConfig);
    }

    @Test
    void identity_forbiddenWhenNoPrincipal() {
        ResponseEntity<?> resp = controller.identity(new MockHttpServletRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // ── createToken ────────────────────────────────────────────────────────

    @Test
    void createToken_happyPathWithRole() {
        when(tokenService.createToken(eq("svc1"), eq(AuthRole.WRITER), eq(30))).thenReturn("tok-abc");

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("svc1", "writer", 30), adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?,?> body = (Map<?,?>) resp.getBody();
        assertEquals("tok-abc", body.get("token"));
        assertEquals("writer", body.get("role"));
    }

    @Test
    void createToken_defaultsToWriterWhenRoleMissing() {
        when(tokenService.createToken(any(), eq(AuthRole.WRITER), any())).thenReturn("tok");

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("svc", null, null), adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void createToken_invalidRoleReturns400() {
        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("svc", "wizard", null), adminRequest());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(tokenService);
    }

    @Test
    void createToken_duplicateNameReturns409() {
        when(tokenService.createToken(any(), any(), any()))
                .thenThrow(new IllegalStateException("name taken"));

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("dup", "writer", null), adminRequest());

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("name taken", ((Map<?,?>) resp.getBody()).get("error"));
    }

    @Test
    void createToken_forbiddenForNonAdmin() {
        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("svc", "writer", null), writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(tokenService);
    }

    // ── listTokens ─────────────────────────────────────────────────────────

    @Test
    void listTokens_returnsTokenList() {
        when(tokenService.listTokens(false, 200)).thenReturn(List.of());

        var resp = controller.listTokens(adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(((Map<?,?>) resp.getBody()).get("tokens"));
        verify(tokenService).listTokens(false, 200);
    }

    @Test
    void listTokens_forbiddenForNonAdmin() {
        var resp = controller.listTokens(writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // ── revokeToken ────────────────────────────────────────────────────────

    @Test
    void revokeToken_happyPath() {
        var resp = controller.revokeToken("svc1", adminRequest());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, ((Map<?,?>) resp.getBody()).get("revoked"));
        verify(tokenService).revokeToken("svc1");
    }

    @Test
    void revokeToken_unknownNameReturns404() {
        doThrow(new IllegalStateException("not found")).when(tokenService).revokeToken("ghost");

        var resp = controller.revokeToken("ghost", adminRequest());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void revokeToken_forbiddenForNonAdmin() {
        var resp = controller.revokeToken("svc1", writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(tokenService);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static HttpServletRequest adminRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE,
                new AuthPrincipal("admin-test", AuthRole.ADMIN));
        return r;
    }

    private static HttpServletRequest writerRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE,
                new AuthPrincipal("writer-test", AuthRole.WRITER));
        return r;
    }
}
