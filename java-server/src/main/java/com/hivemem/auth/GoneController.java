package com.hivemem.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access mode replacement for the legacy login endpoints. Without this, SpaController's
 * method-agnostic catch-all would forward /login and /logout to index.html with a 200,
 * leaving stale PWA shells silently broken instead of diagnosable.
 */
@RestController
@ConditionalOnProperty(name = "hivemem.access.enabled", havingValue = "true")
public class GoneController {

    @RequestMapping({"/login", "/logout"})
    public ResponseEntity<Void> gone() {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }
}
