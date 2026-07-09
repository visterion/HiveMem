package com.hivemem.consumption;

import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Pass 2 of the 3-pass reassembly: read ONE upright page and extract assembly-relevant metadata.
 *  One image per call on purpose: per-page calls need no image labels (Haiku cannot reliably map
 *  many unlabeled images to page numbers) and reading upright pages is what made extraction
 *  error-free in validation. */
public class PageMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PageMetadataExtractor.class);

    /** What pass 3 needs to assemble mailings. All String fields nullable. */
    public record PageMetadata(int page, String sender, String date, String pageLabel,
                               String docType, String reference, String summary, boolean blank) {}

    static final String PROMPT = """
            This is ONE page of a scanned German letter/document batch. Read it and extract:
            - sender: the sender/letterhead (company or authority) printed on the page, else null
            - date: the letter date printed on the page (not "Stand"/print dates of generic notices
              — if only a "Stand: ..." date exists, report that but prefix it with "Stand ")
            - page_label: the page number PRINTED on the page (e.g. "Seite 2 von 2", "2/3");
              vertical print-shop control strings along the edge do NOT count; else null
            - doc_type: short type, e.g. "letter", "contract data sheet", "SEPA mandate",
              "Datenschutz notice", "Widerruf notice", "invoice", "Bescheid", "blank"
            - reference: any contract/customer/file number (Vertrags-Nr, Kunden-Nr, Buchungs-Nr,
              Steuernummer...), else null
            - summary: one short sentence of what the page is
            - blank: true if the page is essentially empty
            Reply with STRICT JSON only:
            {"sender":...,"date":...,"page_label":...,"doc_type":...,"reference":...,"summary":...,"blank":...}""";

    private final VisionMultiClient vision;

    public PageMetadataExtractor(VisionMultiClient vision) {
        this.vision = vision;
    }

    /** Extract metadata for one upright page. Never throws: after one retry it returns a null-row
     *  (sheet pairing in pass 3 still places the page next to its scan neighbors). */
    public PageMetadata extract(String realm, int page, byte[] uprightPng) {
        List<VisionMultiClient.Image> images = List.of(
                new VisionMultiClient.Image("image/png", Base64.getEncoder().encodeToString(uprightPng)));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode n = LlmJson.parseObject(vision.group(realm, PROMPT, images));
                return new PageMetadata(page,
                        n.path("sender").asString(null),
                        n.path("date").asString(null),
                        n.path("page_label").asString(null),
                        n.path("doc_type").asString(null),
                        n.path("reference").asString(null),
                        n.path("summary").asString(null),
                        n.path("blank").asBoolean(false));
            } catch (Exception e) {
                log.warn("Metadata attempt {}/2 failed for page {}: {}", attempt, page, e.toString());
            }
        }
        return new PageMetadata(page, null, null, null, null, null, null, false);
    }
}
