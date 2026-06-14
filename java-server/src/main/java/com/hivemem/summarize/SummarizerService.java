package com.hivemem.summarize;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.extraction.FactSpec;
import com.hivemem.extraction.PreClassifier;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
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
        String lang = (language == null || language.isBlank()) ? instanceDefault : language;
        return "en".equalsIgnoreCase(lang.trim()) ? "tax-relevant" : "steuerrelevant";
    }

    private final SummarizerProperties props;
    private final ExtractionProperties extractionProps;
    private final SummarizerRepository repo;
    private final SummarizeBudgetTracker budget;
    private final AnthropicSummarizer anthropic;
    private final WriteToolService writeService;
    private final ExtractionProfileRegistry profileRegistry;

    public SummarizerService(SummarizerProperties props,
                             ExtractionProperties extractionProps,
                             SummarizerRepository repo,
                             DSLContext dsl,
                             RestClient.Builder builder,
                             WriteToolService writeService,
                             ExtractionProfileRegistry profileRegistry) {
        this.props = props;
        this.extractionProps = extractionProps;
        this.repo = repo;
        this.budget = new SummarizeBudgetTracker(dsl, props.getDailyBudgetUsd());
        this.anthropic = new AnthropicSummarizer(builder, props);
        this.writeService = writeService;
        this.profileRegistry = profileRegistry;
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

    @Scheduled(fixedRateString = "${hivemem.summarize.backfill-interval-ms:300000}")
    public void backfill() {
        if (!budget.canSpend()) return;
        List<UUID> ids = repo.findCellsNeedingSummary(props.getBackfillBatchSize());
        for (UUID id : ids) {
            if (!budget.canSpend()) break;
            summarizeOne(id);
        }
    }

    void summarizeOne(UUID cellId) {
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

            // Store document_type on the (new) cell row.
            String docType = result.documentType() != null
                    ? result.documentType() : extractionProps.getDefaultFallbackType();
            repo.setDocumentType(targetId, docType);

            // Store the short LLM title in topic (the document's display name).
            if (result.title() != null && !result.title().isBlank()) {
                repo.setTopic(targetId, result.title().trim());
            }

            // Persist facts.
            persistFacts(targetId, result.facts());

            // Tax-relevance tag (language-correct).
            if (result.taxRelevant()) {
                repo.applyTag(targetId, taxTagFor(result.language(), props.getLanguage()));
            }

            // Set the cell's valid_from from the document's own date, if the LLM gave a usable one.
            result.facts().stream()
                    .filter(f -> "document_date".equals(f.predicate()))
                    .max(java.util.Comparator.comparingDouble(FactSpec::confidence))
                    .flatMap(f -> DocumentDateParser.parse(f.object()))
                    .ifPresent(d -> repo.setValidFrom(
                            targetId, d.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)));

            repo.removeNeedsSummaryTag(cellId);
            if (newId != null) repo.removeNeedsSummaryTag(newId);
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
                String title = anthropic.generateTitle(summary);
                if (title != null && !title.isBlank()) {
                    repo.setTopic(id, title.trim());
                    titled++;
                }
            } catch (Exception e) {
                log.warn("Title backfill failed for cell {}: {}", id, e.getMessage());
            }
        }
        return titled;
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
