package com.hivemem.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hooks")
public class HooksController {

    private static final Logger log = LoggerFactory.getLogger(HooksController.class);

    private final HookContextService service;

    public HooksController(HookContextService service) {
        this.service = service;
    }

    @PostMapping("/context")
    public ResponseEntity<HookContextResponse> context(
            @RequestBody HookContextRequest req,
            @RequestParam(name = "threshold", required = false) Double threshold,
            @RequestParam(name = "maxCells", required = false) Integer maxCells) {
        log.info("HOOK_CALL session={} event={} promptLen={} threshold={} maxCells={}",
                req == null ? "?" : req.session_id(),
                req == null ? "?" : req.hook_event_name(),
                req == null || req.prompt() == null ? 0 : req.prompt().length(),
                threshold, maxCells);
        ContextResult result;
        try {
            result = service.contextFor(req, threshold, maxCells);
        } catch (RuntimeException e) {
            log.warn("Hook context failed; returning empty injection", e);
            result = ContextResult.empty();
        }
        String eventName = req != null && req.hook_event_name() != null
                ? req.hook_event_name() : "UserPromptSubmit";
        return ResponseEntity.ok(
                HookContextResponse.of(eventName, result.formattedContext(), result.citedSources()));
    }
}
