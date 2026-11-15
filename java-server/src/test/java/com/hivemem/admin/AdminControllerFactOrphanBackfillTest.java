package com.hivemem.admin;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.sync.InstanceConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused test for {@code POST /admin/backfill-fact-orphans}, the resumable backfill that settles
 * facts left behind by the deduplicator (see {@link DocumentDedupService#factOrphanBackfill}).
 * Mirrors the sibling {@code dedupBackfill} tests in {@link AdminControllerTest}, but kept in its
 * own class rather than appended there so the two endpoints' cases stay easy to tell apart.
 */
class AdminControllerFactOrphanBackfillTest {

    private InstanceConfig instanceConfig;
    private TokenService tokenService;
    private DocumentDedupService dedup;
    private AdminController controller;
    private static final UUID INSTANCE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        instanceConfig = mock(InstanceConfig.class);
        tokenService = mock(TokenService.class);
        dedup = mock(DocumentDedupService.class);
        when(instanceConfig.instanceId()).thenReturn(INSTANCE_ID);
        // Dedup enabled unless a test says otherwise: most cases exercise the walk itself, not the
        // kill switch, and Mockito's default `false` for a boolean stub would silently route every
        // one of them into the 409 branch instead.
        when(dedup.isEnabled()).thenReturn(true);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.hivemem.summarize.SummarizerService> summarizer =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        controller = new AdminController(instanceConfig, tokenService,
                mock(com.hivemem.attachment.AttachmentChunkRepairService.class), summarizer, dedup,
                mock(EventTimeRepairService.class));
    }

    @Test
    void happyPathForAdmin_reportsAllFieldsAndTheCursor() {
        OffsetDateTime last = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID lastId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(dedup.factOrphanBackfill(isNull(), isNull(), eq(200)))
                .thenReturn(new DocumentDedupService.FactOrphanReport(
                        10, 4, 3, 2, 1, last, lastId, 5));

        var resp = controller.backfillFactOrphans(null, null, 200, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(10, body.get("checked"));
        assertEquals(4, body.get("invalidated"));
        assertEquals(3, body.get("repointed"));
        assertEquals(2, body.get("skipped"));
        assertEquals(1, body.get("failed"));
        assertEquals(5, body.get("remaining"));
        assertEquals(last.toString(), body.get("after_created_at"));
        assertEquals(lastId.toString(), body.get("after_id"));
        verify(dedup).factOrphanBackfill(isNull(), isNull(), eq(200));
    }

    /**
     * The cursor of the previous response is handed straight back to the service unchanged. A
     * controller that silently dropped it and always called with {@code null, null} would pass
     * every other test here — this is the one that would catch it.
     */
    @Test
    void passesTheCursorThrough() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        when(dedup.factOrphanBackfill(eq(cursor), eq(cursorId), eq(50)))
                .thenReturn(new DocumentDedupService.FactOrphanReport(
                        50, 10, 5, 0, 0, cursor, cursorId, 0));

        var resp = controller.backfillFactOrphans(cursor, cursorId, 50, adminRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(dedup).factOrphanBackfill(eq(cursor), eq(cursorId), eq(50));
    }

    /**
     * An exhausted walk (the page came back empty because nothing is left to settle) must still
     * serialise both cursor fields as null, not drop the keys.
     */
    @Test
    void reportsANullCursorWhenTheWalkIsExhausted() {
        when(dedup.factOrphanBackfill(isNull(), isNull(), eq(200)))
                .thenReturn(new DocumentDedupService.FactOrphanReport(
                        0, 0, 0, 0, 0, null, null, 0));

        var resp = controller.backfillFactOrphans(null, null, 200, adminRequest());

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue(body.containsKey("after_created_at"));
        assertNull(body.get("after_created_at"));
        assertTrue(body.containsKey("after_id"));
        assertNull(body.get("after_id"));
    }

    /**
     * Half a cursor is refused, same as the sibling endpoint: the keyset compares the pair, so
     * accepting one half would silently restart the walk at the beginning.
     */
    @Test
    void rejectsAHalfCursor() {
        OffsetDateTime cursor = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        var missingId = controller.backfillFactOrphans(cursor, null, 200, adminRequest());
        var missingTimestamp = controller.backfillFactOrphans(null, cursorId, 200, adminRequest());

        assertEquals(HttpStatus.BAD_REQUEST, missingId.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, missingTimestamp.getStatusCode());
        assertEquals("after_created_at and after_id must be given together",
                ((Map<?, ?>) missingId.getBody()).get("error"));
        verifyNoInteractions(dedup);
    }

    /**
     * The contract tells the operator to loop until remaining == 0 (and skipped/failed == 0), so
     * limit must never be a value that makes the loop spin (0 checks nothing and leaves the cursor
     * untouched) or that reaches Postgres as LIMIT -1. Mirrors
     * {@code AdminControllerTest#dedupBackfill_clampsTheLimitIntoTheUsableRange}.
     */
    @Test
    void clampsTheLimitIntoTheUsableRange() {
        when(dedup.factOrphanBackfill(isNull(), isNull(), anyInt()))
                .thenReturn(new DocumentDedupService.FactOrphanReport(0, 0, 0, 0, 0, null, null, 0));

        controller.backfillFactOrphans(null, null, 0, adminRequest());
        controller.backfillFactOrphans(null, null, -5, adminRequest());
        controller.backfillFactOrphans(null, null, 999_999, adminRequest());

        verify(dedup, times(2)).factOrphanBackfill(isNull(), isNull(), eq(1));
        verify(dedup).factOrphanBackfill(isNull(), isNull(), eq(1000));
    }

    /**
     * The declared default is part of the contract, not an implementation detail. Read off the
     * annotation because a direct call bypasses Spring's parameter binding.
     */
    @Test
    void defaultLimitIsModest() throws Exception {
        var method = AdminController.class.getMethod("backfillFactOrphans", OffsetDateTime.class,
                UUID.class, int.class, jakarta.servlet.http.HttpServletRequest.class);
        var limitParam = method.getParameters()[2]
                .getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);

        assertEquals("limit", limitParam.value());
        assertEquals("200", limitParam.defaultValue());
    }

    @Test
    void forbiddenForNonAdmin() {
        var resp = controller.backfillFactOrphans(null, null, 200, writerRequest());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(dedup);
    }

    /**
     * The all-zero disabled-state report ({@code checked:0, skipped:0, failed:0, remaining:0})
     * satisfies this endpoint's own documented finish condition literally, while every orphan is
     * still live. That is the silent-success trap this backfill exists to remove, so the endpoint
     * must refuse with 409 instead of forwarding the disabled report as a 200.
     */
    @Test
    void refusesWithConflictWhenDedupIsDisabled() {
        when(dedup.isEnabled()).thenReturn(false);

        var resp = controller.backfillFactOrphans(null, null, 200, adminRequest());

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("dedup is disabled; nothing was settled",
                ((Map<?, ?>) resp.getBody()).get("error"));
        verify(dedup).isEnabled();
        verifyNoMoreInteractions(dedup);
    }

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
