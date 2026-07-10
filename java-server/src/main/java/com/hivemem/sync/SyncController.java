package com.hivemem.sync;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final SyncOpsRepository syncOpsRepository;
    private final OpReplayer opReplayer;

    public SyncController(SyncOpsRepository syncOpsRepository, OpReplayer opReplayer) {
        this.syncOpsRepository = syncOpsRepository;
        this.opReplayer = opReplayer;
    }

    @GetMapping("/ops")
    public ResponseEntity<?> getOps(@RequestParam(defaultValue = "0") long since, HttpServletRequest request) {
        if (!isWriterOrAdmin(request)) return forbidden();
        List<OpDto> ops = syncOpsRepository.findOpsAfter(since);
        long maxSeq = ops.isEmpty() ? since : ops.getLast().seq();
        return ResponseEntity.ok(Map.of("ops", ops, "max_seq", maxSeq));
    }

    @PostMapping("/ops")
    public ResponseEntity<?> receiveOps(@RequestBody SyncPushRequest request, HttpServletRequest httpRequest) {
        if (!isWriterOrAdmin(httpRequest)) return forbidden();
        OpReplayer.BatchResult result = opReplayer.replayAll(request.sourcePeer(), request.ops());
        return ResponseEntity.ok(Map.of("replayed", result.replayed(), "skipped", result.skipped(),
                "failed", result.failed()));
    }

    /**
     * Peer sync requires a WRITER (or ADMIN) bearer token. {@code AuthFilter} authenticates
     * {@code /sync} but does no role check, so without this gate any READER/AGENT token could
     * push committed ops via {@code POST /sync/ops} — bypassing the approval/role model
     * (AGENT writes are normally pending until approved; READER cannot write at all). Requiring
     * WRITER+ closes that bypass without breaking peer sync: peer tokens are issued as WRITER
     * (see {@code scripts/connect-peers.sh}), and a WRITER can already make committed writes
     * through normal MCP, so this grants no new privilege.
     *
     * <p>A dedicated PEER role that further constrains which op types a peer may replay
     * (e.g. approve_pending / kg_invalidate) is deferred to the sync-redesign workstream.
     */
    private static boolean isWriterOrAdmin(HttpServletRequest request) {
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return principal != null && (principal.role() == AuthRole.WRITER || principal.role() == AuthRole.ADMIN);
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "writer role required"));
    }
}
