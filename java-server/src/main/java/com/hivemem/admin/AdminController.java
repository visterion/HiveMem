package com.hivemem.admin;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.summarize.SummarizerService;
import com.hivemem.sync.InstanceConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final InstanceConfig instanceConfig;
    private final TokenService tokenService;
    private final com.hivemem.attachment.AttachmentChunkRepairService chunkRepair;
    private final ObjectProvider<SummarizerService> summarizer;

    public AdminController(InstanceConfig instanceConfig, TokenService tokenService,
                           com.hivemem.attachment.AttachmentChunkRepairService chunkRepair,
                           ObjectProvider<SummarizerService> summarizer) {
        this.instanceConfig = instanceConfig;
        this.tokenService = tokenService;
        this.chunkRepair = chunkRepair;
        this.summarizer = summarizer;
    }

    @GetMapping("/identity")
    public ResponseEntity<?> identity(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        return ResponseEntity.ok(Map.of("instance_uuid", instanceConfig.instanceId().toString()));
    }

    @PostMapping("/tokens")
    public ResponseEntity<?> createToken(@RequestBody CreateTokenRequest body, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        AuthRole role;
        try {
            role = AuthRole.fromWireValue(body.role() == null ? "writer" : body.role());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
        }
        try {
            String token = tokenService.createToken(body.name(), role, body.expiresInDays());
            return ResponseEntity.ok(Map.of("name", body.name(), "role", role.wireValue(), "token", token));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tokens")
    public ResponseEntity<?> listTokens(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        return ResponseEntity.ok(Map.of("tokens", tokenService.listTokens(false, 200)));
    }

    @DeleteMapping("/tokens/{name}")
    public ResponseEntity<?> revokeToken(@PathVariable String name, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        try {
            tokenService.revokeToken(name);
            return ResponseEntity.ok(Map.of("name", name, "revoked", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /** One-time repair of attachments whose stored S3 object still has aws-chunked framing. */
    @PostMapping("/attachments/repair-chunked")
    public ResponseEntity<?> repairChunked(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        var r = chunkRepair.repairAll();
        return ResponseEntity.ok(Map.of(
                "scanned", r.scanned(),
                "repaired_originals", r.repairedOriginals(),
                "repaired_thumbnails", r.repairedThumbnails(),
                "failed", r.failed()));
    }

    /** One-shot: give already-summarized documents (topic IS NULL) a short LLM title. */
    @PostMapping("/backfill-titles")
    public ResponseEntity<?> backfillTitles(@RequestParam(value = "limit", defaultValue = "200") int limit,
                                            HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        SummarizerService svc = summarizer.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "summarizer disabled"));
        }
        int titled = svc.backfillTitles(limit);
        return ResponseEntity.ok(Map.of("titled", titled));
    }

    private static boolean isAdmin(HttpServletRequest request) {
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return principal != null && principal.role() == AuthRole.ADMIN;
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "admin role required"));
    }

    public record CreateTokenRequest(String name, String role, Integer expiresInDays) {}
}
