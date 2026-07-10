package com.hivemem.write;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteArgumentParserConfidenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode args(double confidence) {
        ObjectNode node = mapper.createObjectNode();
        node.put("confidence", confidence);
        return node;
    }

    @Test
    void acceptsBoundaryAndInteriorValues() {
        assertThat(WriteArgumentParser.requiredConfidence(args(0.0), "confidence", 1.0)).isEqualTo(0.0);
        assertThat(WriteArgumentParser.requiredConfidence(args(0.5), "confidence", 1.0)).isEqualTo(0.5);
        assertThat(WriteArgumentParser.requiredConfidence(args(1.0), "confidence", 1.0)).isEqualTo(1.0);
    }

    @Test
    void fallsBackToDefaultWhenAbsent() {
        ObjectNode empty = mapper.createObjectNode();
        assertThat(WriteArgumentParser.requiredConfidence(empty, "confidence", 0.7)).isEqualTo(0.7);
        assertThat(WriteArgumentParser.requiredConfidence(null, "confidence", 0.7)).isEqualTo(0.7);
    }

    @Test
    void rejectsValuesOutsideUnitInterval() {
        assertThatThrownBy(() -> WriteArgumentParser.requiredConfidence(args(-0.1), "confidence", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteArgumentParser.requiredConfidence(args(1.5), "confidence", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteArgumentParser.requiredConfidence(args(Double.NaN), "confidence", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericValue() {
        ObjectNode node = mapper.createObjectNode();
        node.put("confidence", "high");
        assertThatThrownBy(() -> WriteArgumentParser.requiredConfidence(node, "confidence", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
