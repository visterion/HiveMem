package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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
        String key = round(event.lat()) + "," + round(event.lon());

        String cached = cache.get(key);
        if (cached != null) {
            repo.updatePlace(event.attachmentId(), cached, "done");
            return;
        }

        throttle();
        Optional<String> name = client.reverse(event.lat(), event.lon());
        if (name.isPresent()) {
            cache.put(key, name.get());
            repo.updatePlace(event.attachmentId(), name.get(), "done");
        } else {
            repo.updatePlace(event.attachmentId(), null, "failed");
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
