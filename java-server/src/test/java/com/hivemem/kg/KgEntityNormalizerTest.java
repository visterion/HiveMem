package com.hivemem.kg;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class KgEntityNormalizerTest {

    @Test
    void lowercasesTrimsAndCollapsesWhitespace() {
        assertThat(KgEntityNormalizer.normalize("  HiveMem   MCP  ")).isEqualTo("hivemem mcp");
    }

    @Test
    void collapsesTabsAndNewlines() {
        assertThat(KgEntityNormalizer.normalize("HiveMem\t9.0.0\n")).isEqualTo("hivemem 9.0.0");
    }

    @Test
    void nullReturnsNull() {
        assertThat(KgEntityNormalizer.normalize(null)).isNull();
    }
}
