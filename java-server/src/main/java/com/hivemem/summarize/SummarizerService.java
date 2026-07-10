package com.hivemem.summarize;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.extraction.FactSpec;
import com.hivemem.extraction.PreClassifier;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.summarize.enabled", havingValue = "true")
public class SummarizerService {

    private static final Logger log = LoggerFactory.getLogger(SummarizerService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-summarizer", AuthRole.ADMIN);

    /**
     * Canonical tax tag for the content language: en → "tax-relevant", de → "steuerrelevant".
     * Unknown/blank language falls back to the instance default language (so a German instance
     * yields "steuerrelevant"). Reused by the backfill.
     */
    static String taxTagFor(String language, String instanceDefault) {
        String fallback = (instanceDefault == null || instanceDefault.isBlank()) ? "de" : instanceDefault;
        String lang = (language == null || language.isBlank()) ? fallback : language;
        return "en".equalsIgnoreCase(lang.trim()) ? "tax-relevant" : "steuerrelevant";
    }

    private final SummarizerProperties props;
    private final ExtractionProperties extractionProps;
    private final SummarizerRepository repo;
    private final SummarizeBudgetTracker budget;
    private final AnthropicSummarizer anthropic;
    private final WriteToolService writeService;
    private final ExtractionProfileRegistry profileRegistry;
    private final DocumentDedupService dedup; // may be null (tests)

    @Autowired
    public SummarizerService(SummarizerProperties props,
                             ExtractionProperties extractionProps,
                             SummarizerRepository repo,
                             DSLContext dsl,
                             RestClient.Builder builder,
                             WriteToolService writeService,
                             ExtractionProfileRegistry profileRegistry,
                             DocumentDedupService dedup) {
        this(props, extractionProps, repo,
                new SummarizeBudgetTracker(dsl, props.getDailyBudgetUsd()),
                new AnthropicSummarizer(builder, props),
                writeService, profileRegistry, dedup);
    }

    /** Test seam: inject pre-built {@link SummarizeBudgetTracker}/{@link AnthropicSummarizer}
     *  instead of constructing them from a DSLContext/RestClient (mirrors OcrService's pattern). */
    SummarizerService(SummarizerProperties props,
                      ExtractionProperties extractionProps,
                      SummarizerRepository repo,
                      SummarizeBudgetTracker budget,
                      AnthropicSummarizer anthropic,
                      WriteToolService writeService,
                      ExtractionProfileRegistry profileRegistry,
                      DocumentDedupService dedup) {
        this.props = props;
        this.extractionProps = extractionProps;
        this.repo = repo;
        this.budget = budget;
        this.anthropic = anthropic;
        this.writeService = writeService;
        this.profileRegistry = profileRegistry;
        this.dedup = dedup;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCellNeedsSummary(CellNeedsSummaryEvent event) {
        if (!budget.canSpend()) {
            log.info("Summarize budget exhausted; deferring cell {}", event.cellId());
            return;
        }
        summarizeOne(event.cellId());
    }

    @Scheduled(fixedRateString = "${hivemem.summarize.backfill-interval:PT5M}")
    public void backfill() {
        if (!budget.canSpend()) return;
        List<UUID> ids = repo.findCellsNeedingSummary(props.getBackfillBatchSize());
        for (UUID id : ids) {
            if (!budget.canSpend()) break;
            summarizeOne(id);
        }
    }

    void summarizeOne(UUID cellId) {
        // Atomic claim: the AFTER_COMMIT event worker and the scheduled backfill can race on the
        // same cell — without the claim both pay an LLM call and produce competing revisions.
        if (!repo.tryClaim(cellId)) {
            return;
        }
        try {
            summarizeClaimed(cellId);
        } finally {
            repo.clearClaim(cellId);
        }
    }

    private void summarizeClaimed(UUID cellId) {
        var snap = repo.findCellSnapshot(cellId).orElse(null);
        if (snap == null) return;
        if (snap.summary() != null && !snap.summary().isBlank()) {
            repo.removeNeedsSummaryTag(cellId);
            return;
        }
        if (snap.content() == null || snap.content().isBlank()) {
            repo.removeNeedsSummaryTag(cellId);
            return;
        }

        // Choose profile: pre-classify by attachment metadata if available, else from content head.
        ExtractionProfile profile = pickProfile(cellId, snap.content());

        try {
            SummaryResult result = anthropic.summarize(snap.content(), profile);
            budget.recordCall(result.inputTokens(), result.outputTokens());

            if (result.summary() == null || result.summary().isBlank()) {
                // Loop guard: reviseCell(content, null) would re-tag needs_summary on the new
                // revision and reschedule the cell forever. Give up on this cell instead.
                log.warn("Summarizer produced no summary for cell {}; giving up", cellId);
                repo.removeNeedsSummaryTag(cellId);
                return;
            }

            var reviseResult = writeService.reviseCellWithSummary(
                    SYSTEM_PRINCIPAL, cellId, snap.content(), result.summary(),
                    result.keyPoints(), result.insight(), result.tags());

            UUID newId = extractNewId(reviseResult);
            UUID targetId = newId != null ? newId : cellId;

            // Persist facts.
            persistFacts(targetId, result.facts());

            // Consolidated, op-logged metadata update so the enrichment reaches peers (the
            // former direct SummarizerRepository UPDATEs bypassed the op log entirely):
            //  - document_type on the (new) cell row
            //  - the short LLM title in topic (the document's display name)
            //  - valid_from from the document's own date, if the LLM gave a usable one
            //  - the language-correct tax-relevance tag
            //  - 'tax_scanned' to decouple the cell from the one-shot backfill
            String docType = result.documentType() != null
                    ? result.documentType() : extractionProps.getDefaultFallbackType();
            String title = (result.title() != null && !result.title().isBlank())
                    ? result.title().trim() : null;
            OffsetDateTime validFrom = result.facts().stream()
                    .filter(f -> "document_date".equals(f.predicate()))
                    .max(Comparator.comparingDouble(FactSpec::confidence))
                    .flatMap(f -> DocumentDateParser.parse(f.object()))
                    .map(d -> d.atStartOfDay().atOffset(ZoneOffset.UTC))
                    .orElse(null);
            List<String> metaTags = new java.util.ArrayList<>();
            if (result.taxRelevant()) {
                metaTags.add(taxTagFor(result.language(), props.getLanguage()));
            }
            metaTags.add("tax_scanned");
            writeService.updateCellMeta(SYSTEM_PRINCIPAL, targetId, docType, title, validFrom, metaTags);

            repo.removeNeedsSummaryTag(cellId);
            if (newId != null) repo.removeNeedsSummaryTag(newId);

            // The cell now has its embedding (encodeForCell used the fresh summary). This is the
            // first point a long scanned doc can be deduped; the service no-ops for non-consumption
            // cells, so manual/agent summaries are unaffected.
            if (dedup != null && newId != null) {
                dedup.findAndDiscardDuplicate(newId);
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Anthropic 429 for cell {}, marking throttled", cellId);
            repo.tagThrottled(cellId);
        } catch (Exception e) {
            log.warn("Summarize failed for cell {}: {}", cellId, e.getMessage());
        }
    }

    /**
     * One-shot backfill: give already-summarized documents (topic IS NULL) a short title via a
     * cheap title-only LLM call derived from their existing summary. Budget-gated; returns the
     * number of cells titled. Idempotent — a titled cell is no longer returned by the finder.
     */
    public int backfillTitles(int limit) {
        int titled = 0;
        for (UUID id : repo.findDocumentsNeedingTitle(limit)) {
            if (!budget.canSpend()) {
                log.info("Title backfill stopped early: budget exhausted after {} cells", titled);
                break;
            }
            try {
                String summary = repo.findSummary(id);
                if (summary == null || summary.isBlank()) continue;
                AnthropicSummarizer.TitleResult title = anthropic.generateTitle(summary);
                // Charge the call to the daily budget — even when the title comes back blank —
                // so the canSpend() gate actually bounds the backfill.
                budget.recordCall(title.inputTokens(), title.outputTokens());
                if (title.title() != null && !title.title().isBlank()) {
                    writeService.updateCellMeta(SYSTEM_PRINCIPAL, id, null, title.title().trim(), null, null);
                    titled++;
                }
            } catch (Exception e) {
                log.warn("Title backfill failed for cell {}: {}", id, e.getMessage());
            }
        }
        return titled;
    }

    /**
     * One-shot backfill for existing documents: set valid_from from an already-stored
     * document_date fact (no LLM), and tax-tag via a cheap summary-only classification.
     * Budget-gated. Idempotent — processed cells get the 'tax_scanned' marker and are not
     * returned again. NOTE: cells without a stored document_date fact are NOT re-extracted
     * from full text here (too expensive); only their tax tag is backfilled.
     */
    public int backfillTaxAndDate(int limit) {
        int processed = 0;
        for (UUID id : repo.findDocumentsNeedingTaxScan(limit)) {
            if (!budget.canSpend()) {
                log.info("Tax/date backfill stopped early: budget exhausted after {} cells", processed);
                break;
            }
            try {
                OffsetDateTime validFrom = null;
                String dateFact = repo.findDocumentDateFact(id);
                if (dateFact != null) {
                    validFrom = DocumentDateParser.parse(dateFact)
                            .map(d -> d.atStartOfDay().atOffset(ZoneOffset.UTC))
                            .orElse(null);
                }
                List<String> metaTags = new java.util.ArrayList<>();
                String summary = repo.findSummary(id);
                if (summary != null && !summary.isBlank()) {
                    var c = anthropic.classifyTaxRelevance(summary);
                    // Charge the classifier call to the daily budget (see backfillTitles).
                    budget.recordCall(c.inputTokens(), c.outputTokens());
                    if (c.taxRelevant()) {
                        metaTags.add(taxTagFor(c.language(), props.getLanguage()));
                    }
                }
                metaTags.add("tax_scanned");
                writeService.updateCellMeta(SYSTEM_PRINCIPAL, id, null, null, validFrom, metaTags);
                processed++;
            } catch (Exception e) {
                log.warn("Tax/date backfill failed for cell {}: {}", id, e.getMessage());
            }
        }
        return processed;
    }

    private ExtractionProfile pickProfile(UUID cellId, String content) {
        if (!extractionProps.isEnabled()) {
            return profileRegistry.fallback();
        }
        String mime = null, filename = null;
        var meta = repo.findCellAttachmentMeta(cellId).orElse(null);
        if (meta != null) {
            mime = meta.mimeType();
            filename = meta.filename();
        }
        String head200 = content.length() > 200 ? content.substring(0, 200) : content;
        String hint = PreClassifier.guessType(mime, filename, head200);
        return profileRegistry.resolve(hint);
    }

    private void persistFacts(UUID cellId, List<FactSpec> facts) {
        if (facts == null || facts.isEmpty()) return;
        for (FactSpec f : facts) {
            try {
                writeService.kgAdd(
                        SYSTEM_PRINCIPAL,
                        cellId.toString(),
                        f.predicate(),
                        f.object(),
                        f.confidence(),
                        cellId,
                        "committed",
                        OffsetDateTime.now(),
                        "insert");
            } catch (Exception e) {
                log.warn("kg_add failed for cell {} predicate {}: {}",
                        cellId, f.predicate(), e.getMessage());
            }
        }
    }

    private static UUID extractNewId(java.util.Map<String, Object> reviseResult) {
        Object newIdObj = reviseResult.get("new_id");
        if (newIdObj == null) return null;
        try {
            return UUID.fromString(newIdObj.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
