package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextSimilarityTest {

    // A realistic, long-ish German invoice used as the "same document" baseline.
    private static final String INVOICE =
            "Rechnung Nummer 4711 vom 12.06.2026 Kundennummer 88123 "
            + "Leistungszeitraum Mai 2026 Position 1 Beratungsleistung Softwarearchitektur "
            + "Menge 3 Stunden Einzelpreis 90,00 EUR Summe 270,00 EUR "
            + "Position 2 Projektmanagement Menge 2 Stunden Einzelpreis 80,00 EUR Summe 160,00 EUR "
            + "Zwischensumme 430,00 EUR zzgl 19 Prozent Umsatzsteuer 81,70 EUR "
            + "Gesamtbetrag 511,70 EUR zahlbar innerhalb von 14 Tagen ohne Abzug";

    @Test
    void identicalTextIsOne() {
        String t = "[page=1]\n" + INVOICE;
        assertEquals(1.0, TextSimilarity.similarity(t, t), 1e-9);
    }

    @Test
    void pageMarkersAndWhitespaceAreIgnored() {
        String a = "[page=1]\nRechnung Nr 4711   Betrag 199,00 EUR faellig am 01.07.2026";
        String b = "Rechnung   Nr 4711 Betrag 199,00 EUR faellig am 01.07.2026\n\n[page=2]";
        // Same words, only markers/whitespace differ -> normalized strings are identical -> 1.0.
        assertEquals(1.0, TextSimilarity.similarity(a, b), 1e-9);
    }

    @Test
    void ocrNoiseVariantStillAboveThreshold() {
        // Same invoice re-scanned: a single OCR character error ("Umsatzsteuer" -> "Umsatzsteuei").
        String b = INVOICE.replace("Umsatzsteuer", "Umsatzsteuei");
        double s = TextSimilarity.similarity(INVOICE, b);
        assertTrue(s >= 0.85, "expected >=0.85, got " + s);
    }

    @Test
    void distinctDocumentsAreBelowThreshold() {
        String b = "Mietvertrag zwischen Vermieter und Mieter Wohnung Hauptstrasse 5 Erdgeschoss "
                + "Wohnflaeche 72 Quadratmeter Kaltmiete 1200 EUR monatlich Nebenkosten 250 EUR "
                + "Kaution drei Kaltmieten Kuendigungsfrist drei Monate Mietbeginn 01.08.2026";
        double s = TextSimilarity.similarity(INVOICE, b);
        assertTrue(s < 0.85, "expected <0.85, got " + s);
    }

    @Test
    void blankNeverMatches() {
        assertEquals(0.0, TextSimilarity.similarity("", ""), 1e-9);
        assertEquals(0.0, TextSimilarity.similarity("   \n  ", "Rechnung Nr 4711"), 1e-9);
        assertEquals(0.0, TextSimilarity.similarity("[page=1]\n[page=2]", "Rechnung Nr 4711"), 1e-9);
    }
}
