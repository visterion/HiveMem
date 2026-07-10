package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Async reverse-geocoding: resolves a place name for an image's GPS and persists it. */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private final NominatimClient client;
    private final ImageMetaRepository repo;
    private final GeocodingProperties props;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile long lastRequestMs = 0L;
    private volatile boolean cacheSeeded = false;

    public GeocodingService(NominatimClient client, ImageMetaRepository repo, GeocodingProperties props) {
        this.client = client;
        this.repo = repo;
        this.props = props;
    }

    // AFTER_COMMIT so a rolled-back ingest never leaves a stale geocode write (the
    // attachment_image_meta row would not exist). fallbackExecution=true so events
    // published outside a transaction (the startup backfill) are still handled.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGeocodeRequested(GeocodeRequestedEvent event) {
        if (!props.isEnabled()) return;
        seedCacheOnce();
        String key = round(event.lat()) + "," + round(event.lon());

        String cached = cache.get(key);
        if (cached != null) {
            repo.updatePlace(event.attachmentId(), cached, "done");
            return;
        }

        throttle();
        try {
            Optional<String> name = client.reverse(event.lat(), event.lon());
            if (name.isPresent()) {
                cache.put(key, name.get());
                repo.updatePlace(event.attachmentId(), name.get(), "done");
            } else {
                // Nominatim answered but knows no place here — a definitive negative.
                repo.updatePlace(event.attachmentId(), null, "failed");
            }
        } catch (Exception e) {
            // Transient failure (network/HTTP): keep geocode_status = 'pending' so the
            // hourly retry sweep revisits it instead of freezing it as 'failed' forever.
            log.warn("Reverse-geocode failed for attachment {} ({}): {} — will retry",
                    event.attachmentId(), key, e.getMessage());
        }
    }

    /** Hourly retry of geocodes stuck in 'pending' (transient failures, restarts, missed events). */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    public void retryPendingGeocodes() {
        if (!props.isEnabled()) return;
        for (ImageMetaRepository.PendingGeocode row : repo.findPendingGeocodes(50)) {
            onGeocodeRequested(new GeocodeRequestedEvent(row.attachmentId(), row.gpsLat(), row.gpsLon()));
        }
    }

    /** Seed the in-memory coordinate→place cache from already-resolved rows, once per boot. */
    private void seedCacheOnce() {
        if (cacheSeeded) return;
        synchronized (this) {
            if (cacheSeeded) return;
            try {
                for (ImageMetaRepository.ResolvedPlace p : repo.findResolvedPlaces()) {
                    cache.putIfAbsent(round(p.gpsLat()) + "," + round(p.gpsLon()), p.placeName());
                }
            } catch (Exception e) {
                log.warn("Geocode cache seeding failed (continuing without): {}", e.getMessage());
            }
            cacheSeeded = true;
        }
    }

    /** Round to 3 decimals (~110 m) to share lookups between near-identical coordinates. */
    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Respect Nominatim's <=1 request/second usage policy. */
    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = 1000L - (now - lastRequestMs);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastRequestMs = System.currentTimeMillis();
    }
}
