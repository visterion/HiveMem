package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EmlAttachmentParserTest {

    private final EmlAttachmentParser parser = new EmlAttachmentParser();

    @Test
    void supportsEml() {
        assertThat(parser.supports("message/rfc822")).isTrue();
        assertThat(parser.supports("application/pdf")).isFalse();
    }

    @Test
    void extractsSubjectAndBody() throws Exception {
        String eml = """
                From: sender@example.com
                To: receiver@example.com
                Subject: Test Email Subject
                Content-Type: text/plain; charset=UTF-8
                
                This is the email body text.
                """;
        ParseResult result = parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extractedText()).contains("Test Email Subject");
        assertThat(result.extractedText()).contains("This is the email body text.");
        assertThat(result.hasThumbnail()).isFalse();
    }

    @Test
    void htmlOnlyEmailFallsBackToStrippedHtmlBody() throws Exception {
        String eml = """
                From: sender@example.com
                To: receiver@example.com
                Subject: HTML only
                Content-Type: text/html; charset=UTF-8

                <html><body><p>Hello <b>world</b> &amp; friends</p><script>evil()</script></body></html>
                """;
        ParseResult result = parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extractedText()).contains("Hello world & friends");
        assertThat(result.extractedText()).doesNotContain("<p>");
        assertThat(result.extractedText()).doesNotContain("evil()");
    }

    @Test
    void plainTextPreferredOverHtmlInMultipartAlternative() throws Exception {
        String eml = """
                From: sender@example.com
                Subject: Alternative
                Content-Type: multipart/alternative; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                plain body
                --BOUND
                Content-Type: text/html; charset=UTF-8

                <p>html body</p>
                --BOUND--
                """;
        ParseResult result = parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extractedText()).contains("plain body");
        assertThat(result.extractedText()).doesNotContain("html body");
    }
}
