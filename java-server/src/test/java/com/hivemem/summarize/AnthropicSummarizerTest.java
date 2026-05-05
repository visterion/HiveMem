package com.hivemem.summarize;

import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicSummarizerTest {

    MockVistierieServer mock;
    AnthropicSummarizer summarizer;

    private static ExtractionProfile minimalProfile() {
        return new ExtractionProfile(
                "other", "default analysis prompt",
                List.of("topic"), List.of(), null, List.of());
    }

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        summarizer = new AnthropicSummarizer(
                RestClient.builder(),
                mock.baseUrl(),
                "test-token",
                "claude-haiku-4-5",
                8000);
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void delegatesToVistierie() {
        // Vistierie returns a JSON payload in "text" that AnthropicSummarizer parses as SummaryResult
        mock.stubComplete(
                "{\\\"summary\\\":\\\"Cell about widgets.\\\",\\\"key_points\\\":[\\\"Has 3 widgets\\\",\\\"Color is red\\\"]," +
                "\\\"insight\\\":\\\"Widgets are fragile.\\\",\\\"tags\\\":[\\\"widgets\\\",\\\"red\\\"],\\\"facts\\\":[]}");

        SummaryResult r = summarizer.summarize("Some long text", minimalProfile());

        assertThat(r.summary()).isEqualTo("Cell about widgets.");
        assertThat(r.keyPoints()).containsExactly("Has 3 widgets", "Color is red");
        assertThat(r.insight()).isEqualTo("Widgets are fragile.");
        assertThat(r.tags()).containsExactly("widgets", "red");
        assertThat(r.inputTokens()).isEqualTo(10);
        assertThat(r.outputTokens()).isEqualTo(3);
    }

    @Test
    void summarizeWithProfileParsesDocumentTypeAndFacts() {
        mock.stubComplete(
                "{\\\"summary\\\":\\\"Stadtwerke 234.56 EUR\\\",\\\"key_points\\\":[]," +
                "\\\"insight\\\":null,\\\"tags\\\":[\\\"invoice\\\"],\\\"document_type\\\":\\\"invoice\\\"," +
                "\\\"facts\\\":[{\\\"predicate\\\":\\\"vendor\\\",\\\"object\\\":\\\"Stadtwerke M\\\\u00fcnchen\\\",\\\"confidence\\\":0.98}," +
                "{\\\"predicate\\\":\\\"amount_total\\\",\\\"object\\\":\\\"234.56\\\",\\\"confidence\\\":0.99}]}");

        ExtractionProfile profile = new ExtractionProfile(
                "invoice", "extract invoice fields", List.of("vendor", "amount_total"),
                List.of(), null, List.of("invoice"));

        SummaryResult r = summarizer.summarize("dummy content", profile);

        assertThat(r.documentType()).isEqualTo("invoice");
        assertThat(r.facts()).hasSize(2);
        assertThat(r.facts().get(0).predicate()).isEqualTo("vendor");
        assertThat(r.facts().get(0).object()).isEqualTo("Stadtwerke München");
        assertThat(r.facts().get(0).confidence()).isCloseTo(0.98, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void summarizeWithProfileTreatsMissingFactsAsEmpty() {
        mock.stubComplete(
                "{\\\"summary\\\":\\\"x\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[]}");

        ExtractionProfile profile = new ExtractionProfile(
                "other", "p", List.of("topic"), List.of(), null, List.of());
        SummaryResult r = summarizer.summarize("dummy", profile);

        assertThat(r.documentType()).isNull();
        assertThat(r.facts()).isEmpty();
    }
}
