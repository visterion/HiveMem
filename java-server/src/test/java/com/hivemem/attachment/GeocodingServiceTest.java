package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class GeocodingServiceTest {

    private final NominatimClient client = mock(NominatimClient.class);
    private final ImageMetaRepository repo = mock(ImageMetaRepository.class);
    private final GeocodingProperties props = new GeocodingProperties();

    private GeocodingService service() { return new GeocodingService(client, repo, props); }

    @Test
    void resolvesAndPersistsPlaceName() {
        when(client.reverse(49.487, 8.466)).thenReturn(Optional.of("Mannheim, DE"));
        UUID id = UUID.randomUUID();
        service().onGeocodeRequested(new GeocodeRequestedEvent(id, 49.487, 8.466));
        verify(repo).updatePlace(id, "Mannheim, DE", "done");
    }

    @Test
    void emptyResultMarksFailed() {
        when(client.reverse(anyDouble(), anyDouble())).thenReturn(Optional.empty());
        UUID id = UUID.randomUUID();
        service().onGeocodeRequested(new GeocodeRequestedEvent(id, 1.0, 2.0));
        verify(repo).updatePlace(id, null, "failed");
    }

    @Test
    void cacheAvoidsSecondLookupForSameRoundedCoords() {
        when(client.reverse(anyDouble(), anyDouble())).thenReturn(Optional.of("Mannheim, DE"));
        GeocodingService s = service();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.onGeocodeRequested(new GeocodeRequestedEvent(a, 49.48740, 8.46601));
        s.onGeocodeRequested(new GeocodeRequestedEvent(b, 49.48738, 8.46604)); // rounds to same key
        verify(client, times(1)).reverse(anyDouble(), anyDouble());
        verify(repo).updatePlace(a, "Mannheim, DE", "done");
        verify(repo).updatePlace(b, "Mannheim, DE", "done");
    }

    @Test
    void disabledTogglePersistsNothing() {
        props.setEnabled(false);
        service().onGeocodeRequested(new GeocodeRequestedEvent(UUID.randomUUID(), 1.0, 2.0));
        verifyNoInteractions(client);
        verifyNoInteractions(repo);
    }
}
