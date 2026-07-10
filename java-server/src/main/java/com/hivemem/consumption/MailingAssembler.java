package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Pass 3 of the 3-pass reassembly: text-only assembly of per-page metadata into mailings.
 *  Grouping is a reasoning task over extracted facts, not a vision task — Haiku scored 5/5 here
 *  while every all-in-one vision variant failed. Sheet-pairing rule comes FIRST in the prompt;
 *  that wording is what fixed duplicate-enclosure assignment. Throws on unparseable output so the
 *  orchestrator's degrade-to-pending path takes over. */
public class MailingAssembler {

    private static final Logger log = LoggerFactory.getLogger(MailingAssembler.class);

    static final String PROMPT = """
            Below are per-page descriptions of ONE scanned batch (a stack of several
            separate letters was scanned front+back on a duplex scanner).

            Physical constraint of duplex scanning — APPLY THIS FIRST, before any content rule:
            consecutive page pairs form one physical sheet (pages 1+2 = sheet 1 front/back,
            3+4 = sheet 2, ...). Both pages of a sheet ALWAYS belong to the SAME mailing. Assign
            each sheet to a mailing based on whichever of its two pages is clearly identifiable
            (a letter, a data sheet with a contract number, a dated Bescheid); the sheet's other
            page — enclosure, generic notice (Datenschutz, Widerruf, terms), or blank — follows
            its sheet partner into that mailing. Generic/undated enclosure pages and blanks are
            NEVER their own mailing. Only exception: the scanner may silently drop a fully blank
            back side, which shifts pairing for the pages AFTER the drop — if the pairing produces
            an impossible sheet (two pages that are clearly fronts of different senders' letters),
            re-anchor the pairing there.

            A MAILING = everything that arrived in one envelope: the letter itself PLUS its
            enclosures (data sheets, SEPA mandate, Datenschutz/privacy notice, Widerruf notice,
            contract terms). Enclosures carry their own print dates ("Stand ...") — that does NOT
            make them separate mailings. Two letters from the same sender with different LETTER
            dates are two different mailings; identical enclosure copies then belong to the mailing
            they were scanned adjacent to.

            Pages:
            %s

            Group ALL pages into mailings. Reply with STRICT JSON only:
            [{"mailing":"<short id>","description":"<sender + what it is + letter date>",
              "confidence":<0.0-1.0>,
              "pages":[<global page numbers in reading order: letter first, then its
              continuation pages by printed page label, then enclosures; blank pages last>]}]
            Every page exactly once across all mailings.

            Additional hard rules:
            - Printed page-label continuity: pages of the SAME sender whose printed labels form one
              continuous sequence are ONE document; body dates never split such a sequence.
            - It is FORBIDDEN to output two mailings with the same sender and the same letter date —
              merge them into one.
            - Enclosures from an affiliated authority/organization (e.g. a Datenschutz notice of the
              state tax administration inside a Finanzamt mailing) belong to the main mailing.
            - Every page must appear exactly once — re-check your output against the page list before
              answering.""";

    private final CompleteClient client;

    public MailingAssembler(CompleteClient client) {
        this.client = client;
    }

    /** Assemble mailings from per-page metadata. DocGroup.pages keeps the model's reading order;
     *  minConfidence carries the mailing confidence (drives committed vs pending downstream). */
    public List<DocGroup> assemble(String realm, List<PageMetadataExtractor.PageMetadata> pages) {
        StringBuilder rows = new StringBuilder();
        for (PageMetadataExtractor.PageMetadata m : pages) {
            rows.append("- page ").append(m.page())
                    .append(": sender=").append(pyRepr(m.sender()))
                    .append(", date=").append(pyRepr(m.date()))
                    .append(", printed_page_label=").append(pyRepr(m.pageLabel()))
                    .append(", blank=").append(m.blank())
                    .append(", reference=").append(pyRepr(m.reference()))
                    .append(", content=").append(pyRepr(m.docType()))
                    .append(" - ").append(pyRepr(m.summary()))
                    .append('\n');
        }
        String prompt = PROMPT.formatted(rows.toString().strip());
        JsonNode arr = null;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                arr = LlmJson.parseArray(client.complete(realm, prompt));
                break;
            } catch (RuntimeException e) {
                log.warn("Assembly attempt {}/2 failed: {}", attempt, e.toString());
                lastException = e;
            }
        }
        if (arr == null) {
            throw lastException;
        }
        List<DocGroup> groups = new ArrayList<>();
        for (JsonNode m : arr) {
            DocGroup g = new DocGroup(m.path("mailing").asString("doc-" + (groups.size() + 1)),
                    m.path("description").asString(null));
            for (JsonNode p : m.path("pages")) g.pages.add(p.asInt());
            g.minConfidence = m.path("confidence").asDouble(0.0);
            groups.add(g);
        }
        return groups;
    }

    /** Render like Python's repr so the rows match the validated prompt format exactly:
     *  null → None, string → '...' (backslashes and single quotes inside the value escaped,
     *  newlines/carriage returns flattened to spaces so a multi-line summary can't break the
     *  one-row-per-page format). */
    private static String pyRepr(String s) {
        return s == null ? "None" : "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", " ").replace("\r", " ") + "'";
    }
}
