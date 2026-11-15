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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AdminControllerTest {

    private InstanceConfig instanceConfig;
    private TokenService tokenService;
    private com.hivemem.consumption.DocumentDedupService dedup;
    private EventTimeRepairService eventTimeRepair;
    private AdminController controller;
    private static final UUID INSTANCE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        instanceConfig = mock(InstanceConfig.class);
        tokenService = mock(TokenService.class);
        dedup = mock(com.hivemem.consumption.DocumentDedupService.class);
        eventTimeRepair = mock(EventTimeRepairService.class);
        when(instanceConfig.instanceId()).thenReturn(INSTANCE_ID);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.hivemem.summarize.SummarizerService> summarizer =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        controller = new AdminController(instanceConfig, tokenService,
                mock(com.hivemem.attachment.AttachmentChunkRepairService.class), summarizer, dedup,
                eventTimeRepair);
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
        when(tokenService.createToken(eq("svc1"), eq(AuthRole.WRITER), eq(30), isNull(), isNull())).thenReturn("tok-abc");

        var resp = controller.createToken(
                new AdminController.CreateTokenRequest("svc1", "writer", 30), adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?,?> body = (Map<?,?>) resp.getBody();
        assertEquals("tok-abc", body.get("token"));
        assertEquals("writer", body.get("role"));
    }

    @Test
    void createToken_defaultsToWriterWhenRoleMissing() {
        when(tokenService.createToken(any(), eq(AuthRole.WRITER), any(), any(), any())).thenReturn("tok");

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
        when(tokenService.createToken(any(), any(), any(), any(), any()))
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

    // ── dedupBackfill ──────────────────────────────────────────────────────

    @Test
    void dedupBackfill_happyPathForAdmin() {
        OffsetDateTime last = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID lastId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(dedup.dedupBackfill(isNull(), isNull(), eq(500)))
                .thenReturn(new com.hivemem.consumption.DocumentDedupService.BackfillReport(
                        10, 3, last, lastId, 7));

        var resp = controller.dedupBackfill(null, null, 500, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?,?> body = (Map<?,?>) resp.getBody();
        assertEquals(10, body.get("checked"));
        assertEquals(3, body.get("discarded"));
        assertEquals(7, body.get("remaining"));
        assertEquals(last.toString(), body.get("after_created_at"));
        assertEquals(lastId.toString(), body.get("after_id"));
        verify(dedup).dedupBackfill(isNull(), isNull(), eq(500));
    }

    /** The cursor of the previous response is handed straight back to the service. */
    @Test
    void dedupBackfill_passesTheCursorThrough() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        when(dedup.dedupBackfill(eq(cursor), eq(cursorId), eq(50)))
                .thenReturn(new com.hivemem.consumption.DocumentDedupService.BackfillReport(
                        50, 0, cursor, cursorId, 0));

        var resp = controller.dedupBackfill(cursor, cursorId, 50, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(dedup).dedupBackfill(eq(cursor), eq(cursorId), eq(50));
    }

    /** A first call has no cursor yet, so both fields must serialise as null, not vanish. */
    @Test
    void dedupBackfill_reportsANullCursorWhenNothingWasVisited() {
        when(dedup.dedupBackfill(isNull(), isNull(), eq(500)))
                .thenReturn(new com.hivemem.consumption.DocumentDedupService.BackfillReport(
                        0, 0, null, null, 0));

        var resp = controller.dedupBackfill(null, null, 500, adminRequest());

        Map<?,?> body = (Map<?,?>) resp.getBody();
        assertTrue(body.containsKey("after_created_at"));
        assertNull(body.get("after_created_at"));
        assertNull(body.get("after_id"));
    }

    /**
     * A half-cursor is the silent-restart trap the keyset was built to avoid, just moved to the
     * edge: with one half missing the walk would start from the beginning again, report the full
     * count as remaining, and signal nothing. It must be refused, not interpreted.
     */
    @Test
    void dedupBackfill_rejectsAHalfCursor() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        var missingId = controller.dedupBackfill(cursor, null, 500, adminRequest());
        var missingTimestamp = controller.dedupBackfill(null, cursorId, 500, adminRequest());

        assertEquals(HttpStatus.BAD_REQUEST, missingId.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, missingTimestamp.getStatusCode());
        verifyNoInteractions(dedup);
    }

    /**
     * The contract tells the operator to loop until remaining == 0, so limit must never be a value
     * that makes the loop spin (0 checks nothing and leaves the cursor untouched) or that reaches
     * Postgres as LIMIT -1. The upper bound exists because the walk is synchronous: at the measured
     * ~150 ms per cell, a page far larger than this can only ever be cut off by a reverse proxy's
     * read timeout, leaving the operator unsure whether the work committed.
     */
    @Test
    void dedupBackfill_clampsTheLimitIntoTheUsableRange() {
        when(dedup.dedupBackfill(isNull(), isNull(), anyInt()))
                .thenReturn(new com.hivemem.consumption.DocumentDedupService.BackfillReport(
                        0, 0, null, null, 0));

        controller.dedupBackfill(null, null, 0, adminRequest());
        controller.dedupBackfill(null, null, -5, adminRequest());
        controller.dedupBackfill(null, null, 999_999, adminRequest());

        verify(dedup, times(2)).dedupBackfill(isNull(), isNull(), eq(1));
        verify(dedup).dedupBackfill(isNull(), isNull(), eq(1000));
    }

    /**
     * The declared default is part of the contract, not an implementation detail: it is the value
     * an operator gets when looping without thinking about page size, and it has to stay short
     * enough to survive a synchronous round trip. Read off the annotation because a direct call
     * bypasses Spring's parameter binding.
     */
    @Test
    void dedupBackfill_defaultLimitIsModest() throws Exception {
        var method = AdminController.class.getMethod("dedupBackfill", OffsetDateTime.class,
                UUID.class, int.class, jakarta.servlet.http.HttpServletRequest.class);
        var limitParam = method.getParameters()[2]
                .getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);

        assertEquals("limit", limitParam.value());
        assertEquals("200", limitParam.defaultValue());
    }

    @Test
    void dedupBackfill_forbiddenForNonAdmin() {
        var resp = controller.dedupBackfill(null, null, 500, writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(dedup);
    }

    // ── backfillEventTime ──────────────────────────────────────────────────

    @Test
    void backfillEventTimeDefaultsToDryRun() throws Exception {
        var param = AdminController.class
                .getMethod("backfillEventTime", boolean.class, int.class, HttpServletRequest.class)
                .getParameters()[0].getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        assertEquals("true", param.defaultValue());
        when(eventTimeRepair.preview(200)).thenReturn(Map.of("dry_run", true));

        var resp = controller.backfillEventTime(Boolean.parseBoolean(param.defaultValue()), 200, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(eventTimeRepair).preview(200);
        verify(eventTimeRepair, never()).repair(anyInt());
    }

    @Test
    void backfillEventTimeClampsLimit() {
        controller.backfillEventTime(true, 0, adminRequest());
        controller.backfillEventTime(true, -5, adminRequest());
        controller.backfillEventTime(true, 100000, adminRequest());

        verify(eventTimeRepair, times(2)).preview(1);
        verify(eventTimeRepair).preview(1000);
    }

    @Test
    void backfillEventTimeRealRunCallsRepair() {
        when(eventTimeRepair.repair(50)).thenReturn(Map.of("dry_run", false));

        var resp = controller.backfillEventTime(false, 50, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(eventTimeRepair).repair(50);
        verify(eventTimeRepair, never()).preview(anyInt());
    }

    @Test
    void backfillEventTimeForbiddenForNonAdmin() {
        var resp = controller.backfillEventTime(false, 50, writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(eventTimeRepair);
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
