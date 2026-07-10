package com.hivemem.attachment;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final AttachmentService service;
    private final AttachmentRepository repo;
    private final AttachmentProperties props;

    public AttachmentController(AttachmentService service, AttachmentRepository repo,
                                AttachmentProperties props) {
        this.service = service;
        this.repo = repo;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("realm") String realm,
                                    @RequestParam(value = "signal",  required = false) String signal,
                                    @RequestParam(value = "topic",   required = false) String topic,
                                    @RequestParam(value = "cell_id", required = false) String cellIdParam,
                                    HttpServletRequest request) throws Exception {
        AuthPrincipal principal = requireAuth(request, AuthRole.WRITER);
        if (!props.isEnabled()) return ResponseEntity.status(503).body(Map.of("error", "Attachment storage not enabled"));
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        if (realm == null || realm.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "realm is required"));

        UUID linkCellId = null;
        if (cellIdParam != null) linkCellId = parseUuid(cellIdParam, "cell_id");

        String mimeType = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");

        Map<String, Object> result;
        try {
            result = service.ingest(
                    file.getInputStream(), file.getOriginalFilename(),
                    mimeType, realm, signal, topic, linkCellId, principal.name());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> metadata(@PathVariable UUID id, HttpServletRequest request) {
        requireAuth(request, AuthRole.READER);
        return repo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id,
                                                        HttpServletRequest request) {
        requireAuth(request, AuthRole.READER);
        Map<String, Object> meta = repo.findById(id).orElse(null);
        if (meta == null) return ResponseEntity.notFound().build();
        long size = ((Number) meta.get("size_bytes")).longValue();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType((String) meta.get("mime_type")));
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.inline()
                        .filename((String) meta.get("original_filename"), java.nio.charset.StandardCharsets.UTF_8)
                        .build());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        // Serve Range requests (Chrome's PDF viewer sends them) ourselves via a ranged S3
        // GET. Returning 206 also keeps Spring's Resource-region machinery out of the way —
        // it cannot handle a one-shot InputStreamResource.
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && size > 0) {
            long start;
            long end; // inclusive
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                HttpRange range = ranges.get(0); // multi-range is rare; serve the first
                start = range.getRangeStart(size);
                end = range.getRangeEnd(size);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                start = -1;
                end = -1;
            }
            if (start < 0 || start >= size || end < start) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .headers(headers).build();
            }
            headers.setContentLength(end - start + 1);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                    .body(new InputStreamResource(service.downloadRange(id, start, end)));
        }

        headers.setContentLength(size);
        return ResponseEntity.ok().headers(headers)
                .body(new InputStreamResource(service.downloadOriginal(id)));
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<InputStreamResource> thumbnail(@PathVariable UUID id,
                                                          HttpServletRequest request) {
        requireAuth(request, AuthRole.READER);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);
            return ResponseEntity.ok().headers(headers)
                    .body(new InputStreamResource(service.downloadThumbnail(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public ResponseEntity<?> listForCell(@RequestParam("cell_id") String cellId,
                                          HttpServletRequest request) {
        requireAuth(request, AuthRole.READER);
        UUID cell = parseUuid(cellId, "cell_id");
        return ResponseEntity.ok(repo.findByCellId(cell));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest request) {
        requireAuth(request, AuthRole.ADMIN);
        boolean deleted = repo.softDelete(id);
        return deleted ? ResponseEntity.ok(Map.of("deleted", id.toString()))
                       : ResponseEntity.notFound().build();
    }

    private AuthPrincipal requireAuth(HttpServletRequest request, AuthRole minRole) {
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (minRole == AuthRole.ADMIN && principal.role() != AuthRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (minRole == AuthRole.WRITER && principal.role() == AuthRole.READER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal;
    }

    private UUID parseUuid(String value, String field) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID for " + field);
        }
    }
}
