package com.hivemem.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchProfileTest {

    @Test
    void everyProfileWeightVectorSumsToOne() {
        for (SearchProfile p : SearchProfile.values()) {
            double sum = p.semantic + p.keyword + p.recency
                    + p.importance + p.popularity + p.graphProximity;
            assertThat(sum)
                    .as("weights of %s sum to 1.0", p.name())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void nullBlankAndWhitespaceResolveToBalanced() {
        assertThat(SearchProfile.fromString(null)).isEqualTo(SearchProfile.BALANCED);
        assertThat(SearchProfile.fromString("")).isEqualTo(SearchProfile.BALANCED);
        assertThat(SearchProfile.fromString("   ")).isEqualTo(SearchProfile.BALANCED);
    }

    @Test
    void fromStringIsCaseInsensitiveAndTrimmed() {
        assertThat(SearchProfile.fromString("SEMANTIC")).isEqualTo(SearchProfile.SEMANTIC);
        assertThat(SearchProfile.fromString("semantic")).isEqualTo(SearchProfile.SEMANTIC);
        assertThat(SearchProfile.fromString(" recent ")).isEqualTo(SearchProfile.RECENT);
    }

    @Test
    void unknownProfileThrowsWithHelpfulMessage() {
        assertThatThrownBy(() -> SearchProfile.fromString("bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown profile")
                .hasMessageContaining("balanced|semantic|recent|important|keyword");
    }

    @Test
    void balancedEqualsHistoricalDefaults() {
        SearchProfile b = SearchProfile.BALANCED;
        assertThat(b.semantic).isEqualTo(0.30);
        assertThat(b.keyword).isEqualTo(0.15);
        assertThat(b.recency).isEqualTo(0.15);
        assertThat(b.importance).isEqualTo(0.15);
        assertThat(b.popularity).isEqualTo(0.15);
        assertThat(b.graphProximity).isEqualTo(0.10);
    }
}
