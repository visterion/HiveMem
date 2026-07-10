package com.hivemem.attachment;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

@Component
public class EmlAttachmentParser implements AttachmentParser {

    @Override
    public boolean supports(String mimeType) {
        return "message/rfc822".equals(mimeType);
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session, content);
        StringBuilder sb = new StringBuilder();

        String subject = message.getSubject();
        if (subject != null) sb.append("Subject: ").append(subject).append("\n");

        String from = message.getHeader("From", null);
        if (from != null) sb.append("From: ").append(from).append("\n\n");

        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        collectBodyParts(message.getContent(), message.getContentType(), plain, html);
        // Prefer text/plain; fall back to a tag-stripped text/html body so HTML-only
        // emails don't lose their content entirely.
        if (plain.length() > 0) {
            sb.append(plain);
        } else if (html.length() > 0) {
            sb.append(htmlToText(html.toString()));
        }
        String text = sb.toString().strip();
        return ParseResult.textOnly(text.isEmpty() ? null : text);
    }

    private void collectBodyParts(Object content, String contentType,
                                  StringBuilder plain, StringBuilder html) throws Exception {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (content instanceof String s) {
            if (ct.startsWith("text/html")) {
                html.append(s).append("\n");
            } else {
                plain.append(s).append("\n");
            }
        } else if (content instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String partCt = part.getContentType().toLowerCase();
                if (partCt.startsWith("text/plain")
                        || partCt.startsWith("text/html")
                        || partCt.startsWith("multipart/")) {
                    collectBodyParts(part.getContent(), partCt, plain, html);
                }
            }
        }
    }

    /** Minimal HTML→text: drop script/style, turn breaks into newlines, strip tags, unescape. */
    private static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n")
             .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
