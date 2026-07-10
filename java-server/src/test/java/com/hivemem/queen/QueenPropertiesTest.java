package com.hivemem.queen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueenPropertiesTest {

    @Test
    void defaultsAreSafe() {
        QueenProperties p = new QueenProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getVistierieBaseUrl()).isEqualTo("http://vistierie:8090");
        assertThat(p.getVistierieToken()).isEmpty();
        assertThat(p.getHivememBaseUrl()).isEqualTo("http://hivemem:8421");
        assertThat(p.getWebhookToken()).isEmpty();
        assertThat(p.getCompletionWebhookToken()).isEmpty();
        assertThat(p.getSchedule()).isEqualTo("0 0 3 * * *");
        assertThat(p.getIsolatedBatchLimit()).isEqualTo(20);
        assertThat(p.getCallTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void settersRoundTrip() {
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setVistierieToken("t");
        p.setWebhookToken("wt");
        p.setCompletionWebhookToken("cwt");
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getVistierieToken()).isEqualTo("t");
        assertThat(p.getWebhookToken()).isEqualTo("wt");
        assertThat(p.getCompletionWebhookToken()).isEqualTo("cwt");
    }

    @Test
    void adminTokenDefaultsEmptyAndIsSettable() {
        QueenProperties p = new QueenProperties();
        org.assertj.core.api.Assertions.assertThat(p.getVistierieAdminToken()).isEmpty();
        p.setVistierieAdminToken("admin-tok");
        org.assertj.core.api.Assertions.assertThat(p.getVistierieAdminToken()).isEqualTo("admin-tok");
    }
}
