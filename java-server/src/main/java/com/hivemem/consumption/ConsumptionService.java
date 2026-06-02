package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionService.class);

    private final ConsumptionProperties props;
    private final AttachmentService attachments;
    private final ConsumptionFileMover mover;

    public ConsumptionService(ConsumptionProperties props, AttachmentService attachments) {
        this.props = props;
        this.attachments = attachments;
        this.mover = new ConsumptionFileMover(Path.of(props.getDir()));
    }

    /** Ingest one stable file. M1: always single document, committed. */
    public void ingestFile(Path file) {
        String filename = file.getFileName().toString();
        try {
            byte[] bytes = Files.readAllBytes(file);  // read fully; stream closed before move
            String mime = URLConnection.guessContentTypeFromName(filename);
            if (mime == null) mime = "application/octet-stream";
            attachments.ingest(new ByteArrayInputStream(bytes), filename, mime, props.getRealm(),
                    null, null, null, "consumption",
                    "committed", "consumption:");
            mover.moveToProcessed(file);
            log.info("Consumed {} -> committed cell in realm {}", filename, props.getRealm());
        } catch (Exception e) {
            log.warn("Consumption ingest failed for {}: {}", filename, e.toString());
            tryMoveFailed(file);
        }
    }

    private void tryMoveFailed(Path file) {
        try { mover.moveToFailed(file); }
        catch (IOException io) { log.error("Could not move {} to failed/: {}", file, io.toString()); }
    }
}
