package com.hivemem.attachment;

import com.hivemem.queen.ArchivistTrigger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentEnrichmentTriggerTest {

    @Test
    void krokiSuccessNotifiesArchivistAfterTagRemoval() {
        AttachmentProperties props = mock(AttachmentProperties.class);
        KrokiClient kroki = mock(KrokiClient.class);
        VisionClient vision = mock(VisionClient.class);
        SeaweedFsClient seaweed = mock(SeaweedFsClient.class);
        AttachmentRepository attachmentRepo = mock(AttachmentRepository.class);
        com.hivemem.write.WriteToolService writeService = mock(com.hivemem.write.WriteToolService.class);
        DSLContext dsl = mock(DSLContext.class);
        com.hivemem.extraction.ExtractionProfileRegistry profiles =
                mock(com.hivemem.extraction.ExtractionProfileRegistry.class);
        VisionBudgetTracker visionBudget = mock(VisionBudgetTracker.class);
        ArchivistTrigger trigger = mock(ArchivistTrigger.class);

        when(kroki.render(anyString(), anyString())).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        AttachmentEnrichmentService svc = new AttachmentEnrichmentService(props, kroki, vision, seaweed,
                attachmentRepo, writeService, dsl, profiles, visionBudget, trigger);

        UUID cellId = UUID.randomUUID();
        // renderAndStore is package-private — callable from this same-package test.
        svc.renderAndStore(UUID.randomUUID(), cellId, "hash", "text/vnd.mermaid", "graph TD; A-->B");

        verify(trigger).maybeTrigger(cellId);
    }
}
