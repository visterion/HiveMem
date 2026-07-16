package com.hivemem.queen;

import com.hivemem.queen.dto.CompletionPayload;
import com.hivemem.queen.dto.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Inbound surface Vistierie calls back into: read-only tool webhooks for the Queen/Bee/Archivist,
 * plus two guarded WRITE webhooks for the Archivist (reclassify_cell, skip_inbox_cell), and the
 * Queen's completion webhook. Path is exempted from {@code AuthFilter}; this controller does its
 * own constant-time bearer-token check against the configured webhook tokens.
 */
@RestController
@RequestMapping("/vistierie")
public class VistierieWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VistierieWebhookController.class);
    private static final String BEARER = "Bearer ";

    private final QueenProperties props;
    private final QueenWebhookService service;
    private final ObjectProvider<com.hivemem.consumption.SeparationApplier> separationApplier;

    public VistierieWebhookController(QueenProperties props, QueenWebhookService service,
            ObjectProvider<com.hivemem.consumption.SeparationApplier> separationApplier) {
        this.props = props;
        this.service = service;
        this.separationApplier = separationApplier;
    }

    @PostMapping("/tools/find_isolated_cells")
    public ResponseEntity<Map<String, Object>> findIsolatedCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        int limit = intInput(req, "limit", props.getIsolatedBatchLimit());
        return output(service.findIsolatedCells(limit));
    }

    @PostMapping("/tools/read_cell")
    public ResponseEntity<Map<String, Object>> readCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.readCell(stringInput(req, "cell_id")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cell_id");
        }
    }

    @PostMapping("/tools/find_inbox_cells")
    public ResponseEntity<Map<String, Object>> findInboxCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        int limit = intInput(req, "limit", props.getInboxBatchLimit());
        return output(service.findInboxCells(limit));
    }

    @PostMapping("/tools/list_taxonomy")
    public ResponseEntity<Map<String, Object>> listTaxonomy(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody(required = false) ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        return output(service.listTaxonomy());
    }

    @PostMapping("/tools/reclassify_cell")
    public ResponseEntity<Map<String, Object>> reclassifyCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.reclassifyInboxCell(
                    stringInput(req, "cell_id"),
                    optInput(req, "realm"), optInput(req, "signal"),
                    optInput(req, "topic"), stringInput(req, "reason")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tools/skip_inbox_cell")
    public ResponseEntity<Map<String, Object>> skipInboxCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.skipInboxCell(stringInput(req, "cell_id"), stringInput(req, "reason")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tools/search_similar_cells")
    public ResponseEntity<Map<String, Object>> searchSimilarCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.searchSimilarCells(stringInput(req, "cell_id"), intInput(req, "limit", 5)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cell_id");
        }
    }

    @PostMapping("/runs/done")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> completion(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody CompletionPayload payload) {
        requireToken(auth, props.getCompletionWebhookToken());
        if (payload == null || !"done".equals(payload.status()) || payload.output() == null) {
            log.info("Queen run {} status={} — nothing to ingest",
                    payload == null ? "?" : payload.run_id(),
                    payload == null ? "?" : payload.status());
            return ResponseEntity.ok().build();
        }
        Object raw = payload.output().get("proposals");
        List<Map<String, Object>> proposals = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        int written = service.ingestProposals(proposals);
        log.info("Queen run {} ingested {} pending tunnel proposal(s)", payload.run_id(), written);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/separation/done")
    public ResponseEntity<Void> separationDone(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody com.hivemem.consumption.SeparationResult payload) {
        requireToken(auth, props.getSeparationWebhookToken());
        if (payload == null || payload.runId() == null) {
            return ResponseEntity.badRequest().build();
        }
        com.hivemem.consumption.SeparationApplier applier = separationApplier.getIfAvailable();
        if (applier == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        applier.apply(payload);
        return ResponseEntity.ok().build();
    }

    private static ResponseEntity<Map<String, Object>> output(Object value) {
        return ResponseEntity.ok(Map.of("output", value));
    }

    private void requireToken(String authHeader, String expected) {
        if (!props.isEnabled() || expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authHeader == null || !authHeader.startsWith(BEARER)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String presented = authHeader.substring(BEARER.length()).trim();
        if (!MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static String stringInput(ToolCallRequest req, String key) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing " + key);
        return String.valueOf(v);
    }

    private static int intInput(ToolCallRequest req, String key, int fallback) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    private static String optInput(ToolCallRequest req, String key) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
