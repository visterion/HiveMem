package com.hivemem.summarize;

import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "document-separator",
                "claude-haiku-4-5",
                8000,
                4096,
                "de");
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
    void sendsVistierieCompleteContract() {
        // Regression: the request used to send {"tenant":...} with a role:"system" entry inside
        // messages, which Vistierie's /llm/complete rejects with 400 (agent_name @NotBlank) and
        // Anthropic rejects (system must be top-level).
        mock.stubComplete("{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        summarizer.summarize("the user content", minimalProfile());

        var requests = findAll(postRequestedFor(urlEqualTo("/llm/complete")));
        assertThat(requests).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(requests.get(0).getBodyAsString());

        assertThat(body.path("agent_name").asText()).isEqualTo("document-separator");
        assertThat(body.has("tenant")).as("must not send the rejected 'tenant' field").isFalse();
        assertThat(body.path("system").asText()).contains("HiveMem");
        assertThat(body.path("messages").size()).as("only the user turn in messages").isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("the user content");
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
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
    void parsesShortTitle() {
        mock.stubComplete(
                "{\\\"title\\\":\\\"Schornsteinfeger-Rechnung 2025\\\",\\\"summary\\\":\\\"s\\\"," +
                "\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        SummaryResult r = summarizer.summarize("content", minimalProfile());

        assertThat(r.title()).isEqualTo("Schornsteinfeger-Rechnung 2025");
    }

    @Test
    void generateTitleReturnsShortTitleFromSummary() {
        mock.stubComplete("Schornsteinfeger-Rechnung 2025");
        String title = summarizer.generateTitle("Rechnung von Schornsteinfegermeister Matthias Fischer …");
        assertThat(title).isEqualTo("Schornsteinfeger-Rechnung 2025");
    }

    @Test
    void generateTitleStripsSurroundingQuotes() {
        mock.stubComplete("\\\"Debeka Beitragsanpassung 2026\\\"");
        String title = summarizer.generateTitle("Debeka Beitragsänderung …");
        assertThat(title).isEqualTo("Debeka Beitragsanpassung 2026");
    }

    @Test
    void parsesMarkdownFencedJsonResponse() {
        // LLMs frequently wrap JSON in ```json … ``` despite the prompt; the summarizer must
        // tolerate it (regression: readTree on the fenced text threw "Failed to parse").
        mock.stubComplete("```json\\n{\\\"summary\\\":\\\"Fenced summary\\\",\\\"key_points\\\":[\\\"kp\\\"]," +
                "\\\"insight\\\":null,\\\"tags\\\":[\\\"x\\\"],\\\"facts\\\":[]}\\n```");

        SummaryResult r = summarizer.summarize("content", minimalProfile());

        assertThat(r.summary()).isEqualTo("Fenced summary");
        assertThat(r.keyPoints()).containsExactly("kp");
        assertThat(r.tags()).containsExactly("x");
    }

    @Test
    void summarizeSatisfiesStrictVistierieContract() {
        // Regression guard: the original mock accepted ANY request body, so four contract bugs
        // (tenant instead of agent_name, role:system inside messages, …) shipped to prod
        // undetected. A strict stub enforces Vistierie's /llm/complete contract: it returns 200
        // ONLY when agent_name+purpose are non-blank, system is top-level, messages is present,
        // and NO message carries role:system. The real summarizer must satisfy all of it.
        mock.stubCompleteStrict(
                "{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        SummaryResult r = summarizer.summarize("real content", minimalProfile());

        assertThat(r.summary()).isEqualTo("s");
    }

    @Test
    void strictStubRejectsLegacyTenantContractShape() {
        // Proves the strict stub has teeth: the pre-9.2.4 body shape (tenant + a role:system
        // entry inside messages, no top-level system) is rejected with 400 — exactly the failure
        // prod returned. Without this, summarizeSatisfiesStrictVistierieContract could pass against
        // a permissive stub and prove nothing.
        mock.stubCompleteStrict(
                "{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        // Force HTTP/1.1 (SimpleClientHttpRequestFactory) — WireMock-standalone mishandles the
        // JDK HttpClient's HTTP/2, surfacing RST_STREAM instead of the 400 (same reason the
        // production test ctor pins this factory).
        RestClient raw = RestClient.builder()
                .baseUrl(mock.baseUrl())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                .build();
        Map<String, Object> legacyBody = Map.of(
                "tenant", "some-tenant",
                "purpose", "summarize_cell",
                "messages", List.of(
                        Map.of("role", "system", "content", "you are a summarizer"),
                        Map.of("role", "user", "content", "the content")));

        assertThatThrownBy(() -> raw.post()
                .uri("/llm/complete")
                .header("content-type", "application/json")
                .body(legacyBody)
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value())
                        .isEqualTo(400));
    }

    @Test
    void systemPromptCarriesLanguageRuleWithDefault() {
        mock.stubComplete("{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        summarizer.summarize("ein deutscher Satz", minimalProfile());

        var requests = findAll(postRequestedFor(urlEqualTo("/llm/complete")));
        assertThat(requests).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(requests.get(0).getBodyAsString());
        String system = body.path("system").asText();
        assertThat(system).contains("SAME LANGUAGE as the cell content");
        assertThat(system).contains("German");
    }

    @Test
    void defaultLanguageIsConfigurable() {
        AnthropicSummarizer english = new AnthropicSummarizer(
                RestClient.builder(), mock.baseUrl(), "test-token",
                "document-separator", "claude-haiku-4-5", 8000, 4096, "en");
        mock.stubComplete("{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        english.summarize("any content", minimalProfile());

        var requests = findAll(postRequestedFor(urlEqualTo("/llm/complete")));
        assertThat(requests).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(requests.get(0).getBodyAsString());
        assertThat(body.path("system").asText()).contains("English");
    }

    @Test
    void languageNameMapsCodesToEnglishNames() {
        assertThat(AnthropicSummarizer.languageName("de")).isEqualTo("German");
        assertThat(AnthropicSummarizer.languageName("en")).isEqualTo("English");
        assertThat(AnthropicSummarizer.languageName("fr")).isEqualTo("fr");
        assertThat(AnthropicSummarizer.languageName("German")).isEqualTo("German");
    }

    @Test
    void parsesLanguageAndTaxRelevant() {
        mock.stubComplete(
                "{\\\"summary\\\":\\\"Handwerkerrechnung.\\\",\\\"key_points\\\":[],\\\"insight\\\":null," +
                "\\\"tags\\\":[],\\\"facts\\\":[],\\\"language\\\":\\\"de\\\",\\\"tax_relevant\\\":true}");

        SummaryResult r = summarizer.summarize("Rechnung über Malerarbeiten", minimalProfile());

        assertThat(r.language()).isEqualTo("de");
        assertThat(r.taxRelevant()).isTrue();
    }

    @Test
    void taxRelevantDefaultsFalseWhenAbsent() {
        mock.stubComplete(
                "{\\\"summary\\\":\\\"s\\\",\\\"key_points\\\":[],\\\"insight\\\":null,\\\"tags\\\":[],\\\"facts\\\":[]}");

        SummaryResult r = summarizer.summarize("x", minimalProfile());

        assertThat(r.taxRelevant()).isFalse();
        assertThat(r.language()).isNull();
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
