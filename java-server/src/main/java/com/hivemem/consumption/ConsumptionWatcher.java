package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionWatcher.class);

    private final ConsumptionProperties props;
    private final ConsumptionService service;
    private final StableFileDetector detector;
    private final Clock clock;

    public ConsumptionWatcher(ConsumptionProperties props, ConsumptionService service) {
        this(props, service, Clock.systemUTC());
    }

    ConsumptionWatcher(ConsumptionProperties props, ConsumptionService service, Clock clock) {
        this.props = props;
        this.service = service;
        this.detector = new StableFileDetector(props.getStableSeconds());
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "#{@consumptionProperties.pollInterval.toMillis()}")
    public void poll() {
        Path dir = Path.of(props.getDir());
        if (!Files.isDirectory(dir)) return;
        long now = clock.millis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;            // skips processed/ failed/ subdirs
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;               // dotfiles / partial uploads
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (detector.isStable(p, a.size(), a.lastModifiedTime().toMillis(), now)) {
                    detector.forget(p);
                    service.ingestFile(p);
                }
            }
        } catch (IOException e) {
            log.warn("Consumption poll failed for {}: {}", dir, e.toString());
        }
    }
}
