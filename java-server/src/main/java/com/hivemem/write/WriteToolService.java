package com.hivemem.write;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSelector;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.summarize.CellNeedsSummaryEvent;
import com.hivemem.summarize.NeedsSummaryDecider;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WriteToolService {

    private static final Logger log = LoggerFactory.getLogger(WriteToolService.class);
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_COMMITTED = "committed";
    private static final String STATUS_REJECTED = "rejected";
    private static final int BULK_SELECTOR_CAP = 1000;
    private static final int BULK_CONFIRM_THRESHOLD = 200;

    private final WriteToolRepository writeToolRepository;
    private final EmbeddingClient embeddingClient;
    private final OpLogWriter opLogWriter;
    private final PushDispatcher pushDispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final CellSelectorRepository cellSelectorRepository;
    private final KgEntityRepository kgEntityRepository;

    public WriteToolService(
            WriteToolRepository writeToolRepository,
            EmbeddingClient embeddingClient,
            OpLogWriter opLogWriter,
            PushDispatcher pushDispatcher,
            ApplicationEventPublisher eventPublisher,
            CellSelectorRepository cellSelectorRepository,
            KgEntityRepository kgEntityRepository
    ) {
        this.writeToolRepository = writeToolRepository;
        this.embeddingClient = embeddingClient;
        this.opLogWriter = opLogWriter;
        this.pushDispatcher = pushDispatcher;
        this.eventPublisher = eventPublisher;
        this.cellSelectorRepository = cellSelectorRepository;
        this.kgEntityRepository = kgEntityRepository;
    }

    @Transactional
    public Map<String, Object> addCell(
            AuthPrincipal principal,
            String content,
            String realm,
            String signal,
            String topic,
            String source,
            List<String> tags,
            Integer importance,
            String summary,
            List<String> keyPoints,
            String insight,
            String actionability,
            String requestedStatus,
            OffsetDateTime validFrom,
            Double dedupeThreshold
    ) {
        String status = effectiveStatus(principal.role(), requestedStatus);
        List<Float> embedding = embeddingClient.encodeForCell(content, summary);

        if (dedupeThreshold != null && embedding != null) {
            // embedding is null when summary is blank/absent AND content exceeds
            // EmbeddingClient.CONTENT_EMBED_MAX_CHARS — skip the dedupe check in that case
            // (the cell is tagged needs_summary below and deduped later once summarized).
            // Serialize concurrent identical adds so the dedupe check-then-insert cannot race
            // (mirrors updateBlueprint's pg_advisory_xact_lock pattern).
            writeToolRepository.advisoryXactLock("cell-dedupe:" + content);
            List<Map<String, Object>> duplicates = writeToolRepository.checkDuplicateCell(
                    embedding.toString(), dedupeThreshold);
            if (!duplicates.isEmpty()) {
                Map<String, Object> rejection = new java.util.LinkedHashMap<>();
                rejection.put("inserted", false);
                rejection.put("duplicates", duplicates);
                return rejection;
            }
        }

        Map<String, Object> inserted = writeToolRepository.addCell(
                content,
                embedding,
                realm,
                signal,
                topic,
                source,
                tags,
                importance,
                summary,
                keyPoints,
                insight,
                actionability,
                status,
                principal.name(),
                validFrom
        );

        if (NeedsSummaryDecider.needsSummary(content, summary)) {
            // The repository returns the cell id as a String (uuidValue → toString),
            // so parse it rather than casting directly to UUID.
            Object idValue = inserted.get("id");
            UUID cellId = idValue == null ? null : UUID.fromString(idValue.toString());
            if (cellId != null) {
                writeToolRepository.tagNeedsSummary(cellId);
                eventPublisher.publishEvent(new CellNeedsSummaryEvent(cellId));
            }
        }

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", inserted.get("id"));
        opPayload.put("realm", realm);
        opPayload.put("signal", signal);
        opPayload.put("topic", topic);
        opPayload.put("source", source);
        opPayload.put("tags", tags);
        opPayload.put("content", content);
        opPayload.put("summary", summary);
        opPayload.put("key_points", keyPoints);
        opPayload.put("insight", insight);
        opPayload.put("importance", importance);
        opPayload.put("actionability", actionability);
        opPayload.put("status", status);
        opPayload.put("agent_id", principal.name());
        opPayload.put("valid_from", validFrom == null ? null : validFrom.toString());
        opPayload.put("dedupe_threshold", dedupeThreshold);
        UUID opId = opLogWriter.append("add_cell", opPayload);
        pushDispatcher.dispatch(opId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("inserted", true);
        result.putAll(inserted);
        return result;
    }

    /**
     * The fact embedding is precomputed BEFORE the advisory lock/transaction open: it's an HTTP
     * call to the embedding service, and must not hold a pooled connection or a lock for the
     * duration of that round trip (same pattern as {@link #kgRenamePredicate} / {@code kgAlias} —
     * see the comment on {@link WriteToolRepository#inTransaction}). The contradiction check does
     * not need the embedding, so it still runs first inside the transaction, unchanged.
     */
    public Map<String, Object> kgAdd(
            AuthPrincipal principal,
            String subject,
            String predicate,
            String object,
            double confidence,
            UUID sourceId,
            String requestedStatus,
            OffsetDateTime validFrom,
            String onConflict
    ) {
        String status = effectiveStatus(principal.role(), requestedStatus);
        String resolvedSubject = kgEntityRepository.resolve(subject);

        String conflictMode = onConflict == null ? "insert" : onConflict;
        if (!conflictMode.equals("insert")
                && !conflictMode.equals("return")
                && !conflictMode.equals("reject")
                && !conflictMode.equals("supersede")) {
            throw new IllegalArgumentException("Invalid on_conflict");
        }

        List<Float> computedEmbedding = null;
        try {
            computedEmbedding = embeddingClient.encodeDocument(resolvedSubject + " " + predicate + " " + object);
        } catch (RuntimeException e) {
            log.warn("Fact embedding unavailable, storing without embedding", e);
        }
        final List<Float> factEmbedding = computedEmbedding;

        return writeToolRepository.inTransaction(() -> {
            int superseded = 0;
            if (!conflictMode.equals("insert")) {
                // Serialize the conflict check-then-supersede against concurrent kg_add on the
                // same (subject, predicate) — otherwise two supersedes can both miss each other's
                // insert.
                writeToolRepository.advisoryXactLock("kg-conflict:" + resolvedSubject + "|" + predicate);
                List<Map<String, Object>> conflicts =
                        writeToolRepository.checkContradiction(resolvedSubject, predicate, object);
                if (!conflicts.isEmpty()) {
                    switch (conflictMode) {
                        case "reject" -> throw new IllegalStateException(
                                "kg_add rejected: conflicting active fact exists");
                        case "return" -> {
                            Map<String, Object> rejection = new java.util.LinkedHashMap<>();
                            rejection.put("inserted", false);
                            rejection.put("conflicts", conflicts);
                            return rejection;
                        }
                        case "supersede" -> {
                            for (Map<String, Object> conflict : conflicts) {
                                UUID conflictId = UUID.fromString(String.valueOf(conflict.get("fact_id")));
                                writeToolRepository.invalidateFact(conflictId);
                                Map<String, Object> invalidatePayload = new java.util.LinkedHashMap<>();
                                invalidatePayload.put("fact_id", conflictId.toString());
                                pushDispatcher.dispatch(opLogWriter.append("kg_invalidate", invalidatePayload));
                            }
                            superseded = conflicts.size();
                        }
                        default -> throw new IllegalStateException("unreachable");
                    }
                }
            }

            Map<String, Object> inserted = writeToolRepository.addFact(
                    resolvedSubject, predicate, object, confidence,
                    sourceId, status, principal.name(), validFrom, factEmbedding);

            Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
            opPayload.put("fact_id", inserted.get("id"));
            opPayload.put("subject", resolvedSubject);
            opPayload.put("predicate", predicate);
            opPayload.put("object", object);
            opPayload.put("confidence", confidence);
            opPayload.put("source_id", sourceId == null ? null : sourceId.toString());
            opPayload.put("status", status);
            opPayload.put("agent_id", principal.name());
            opPayload.put("valid_from", validFrom == null ? null : validFrom.toString());
            UUID opId = opLogWriter.append("kg_add", opPayload);
            pushDispatcher.dispatch(opId);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("inserted", true);
            result.putAll(inserted);
            if (conflictMode.equals("supersede")) {
                result.put("superseded", superseded);
            }
            return result;
        });
    }

    /**
     * Rename a predicate across every active fact matching it (optionally narrowed to a single
     * subject): invalidates each matching fact and re-adds it under the new predicate, preserving
     * subject, object, confidence, source, status and the ORIGINAL valid_from (the fact was true
     * since then; only its name changed). Emits one kg_invalidate + one kg_add op per renamed
     * fact, so OpReplayer replays it identically to a manual invalidate+add.
     *
     * <p>All embeddings are precomputed BEFORE the write transaction opens: up to ~1000 embedding
     * HTTP calls must not hold a pooled connection or row locks for minutes.
     */
    public Map<String, Object> kgRenamePredicate(AuthPrincipal principal, String from, String to,
                                                 String subject, boolean confirm) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("from and to must differ");
        }
        List<Map<String, Object>> facts =
                writeToolRepository.findActiveFactsByPredicate(from, subject, BULK_SELECTOR_CAP + 1);
        if (facts.size() > BULK_SELECTOR_CAP) {
            throw new IllegalArgumentException(
                    "kg_rename_predicate matches more than " + BULK_SELECTOR_CAP + " facts; narrow with subject");
        }
        if (facts.size() > BULK_CONFIRM_THRESHOLD && !confirm) {
            throw new IllegalArgumentException(
                    "kg_rename_predicate would touch " + facts.size() + " facts; pass confirm: true");
        }
        List<List<Float>> embeddings = new java.util.ArrayList<>(facts.size());
        for (Map<String, Object> fact : facts) {
            List<Float> embedding = null;
            try {
                embedding = embeddingClient.encodeDocument(
                        fact.get("subject") + " " + to + " " + fact.get("object"));
            } catch (RuntimeException e) {
                log.warn("Fact embedding unavailable during rename, storing without embedding", e);
            }
            embeddings.add(embedding);
        }
        return writeToolRepository.inTransaction(() -> {
            for (int i = 0; i < facts.size(); i++) {
                Map<String, Object> fact = facts.get(i);
                UUID factId = (UUID) fact.get("id");
                writeToolRepository.invalidateFact(factId);
                Map<String, Object> invalidatePayload = new java.util.LinkedHashMap<>();
                invalidatePayload.put("fact_id", factId.toString());
                pushDispatcher.dispatch(opLogWriter.append("kg_invalidate", invalidatePayload));

                String factSubject = (String) fact.get("subject");
                String object = (String) fact.get("object");
                Map<String, Object> inserted = writeToolRepository.addFact(
                        factSubject, to, object,
                        ((Number) fact.get("confidence")).doubleValue(),
                        (UUID) fact.get("source_id"),
                        (String) fact.get("status"),
                        (String) fact.get("agent_id"),
                        (OffsetDateTime) fact.get("valid_from"),
                        embeddings.get(i));
                Map<String, Object> addPayload = new java.util.LinkedHashMap<>();
                addPayload.put("fact_id", inserted.get("id"));
                addPayload.put("subject", factSubject);
                addPayload.put("predicate", to);
                addPayload.put("object", object);
                addPayload.put("confidence", fact.get("confidence"));
                addPayload.put("source_id", fact.get("source_id") == null ? null : fact.get("source_id").toString());
                addPayload.put("status", fact.get("status"));
                addPayload.put("agent_id", fact.get("agent_id"));
                addPayload.put("valid_from", fact.get("valid_from") == null ? null : fact.get("valid_from").toString());
                pushDispatcher.dispatch(opLogWriter.append("kg_add", addPayload));
            }
            return Map.of("renamed", facts.size(), "matched", facts.size());
        });
    }

    /**
     * Register (or extend) a canonical entity's alias set and retro-migrate every currently active
     * fact whose subject normalizes to one of the aliases onto the canonical subject: invalidates
     * each such fact and re-adds it under the canonical subject, preserving predicate, object,
     * confidence, source, status and the ORIGINAL valid_from. Future kg_add calls resolve the
     * aliases automatically via {@link com.hivemem.kg.KgEntityRepository#resolve}. Emits one
     * kg_invalidate + one kg_add op per migrated fact, so OpReplayer replays it identically to a
     * manual invalidate+add.
     *
     * <p>Migrates ALL active facts matching the aliases regardless of status: pending facts are
     * folded onto the canonical too, each keeping its own status (the same cross-status semantics
     * as {@link #kgRenamePredicate}).
     *
     * <p>The returned {@code resulting_conflicts} is the count of active (subject,predicate) groups
     * on the canonical that have more than one active fact AFTER migration. This includes any
     * pre-existing conflicts already on the canonical before this call — it is not limited to
     * conflicts introduced by this operation. In other words it reports how many (subject,predicate)
     * groups now need human resolution (e.g. a follow-up kg_add with on_conflict=supersede), not
     * merely those this call created.
     *
     * <p>All embeddings are precomputed BEFORE the write transaction opens (see
     * {@link #kgRenamePredicate}).
     */
    public Map<String, Object> kgAlias(AuthPrincipal principal, String canonical,
                                       List<String> aliases, boolean confirm) {
        if (aliases == null || aliases.isEmpty()) {
            throw new IllegalArgumentException("aliases must not be empty");
        }
        List<String> normalized = aliases.stream()
                .map(com.hivemem.kg.KgEntityNormalizer::normalize)
                .distinct()
                .toList();

        List<Map<String, Object>> facts =
                writeToolRepository.findActiveFactsByNormalizedSubjects(normalized, BULK_SELECTOR_CAP + 1);
        if (facts.size() > BULK_SELECTOR_CAP) {
            throw new IllegalArgumentException(
                    "kg_alias matches more than " + BULK_SELECTOR_CAP + " facts; narrow the aliases");
        }
        if (facts.size() > BULK_CONFIRM_THRESHOLD && !confirm) {
            throw new IllegalArgumentException(
                    "kg_alias would migrate " + facts.size() + " facts; pass confirm: true");
        }

        List<List<Float>> embeddings = new java.util.ArrayList<>(facts.size());
        for (Map<String, Object> fact : facts) {
            List<Float> embedding = null;
            try {
                embedding = embeddingClient.encodeDocument(
                        canonical + " " + fact.get("predicate") + " " + fact.get("object"));
            } catch (RuntimeException e) {
                log.warn("Fact embedding unavailable during alias migration, storing without embedding", e);
            }
            embeddings.add(embedding);
        }

        return writeToolRepository.inTransaction(() -> {
            kgEntityRepository.upsert(canonical, aliases, principal.name());

            for (int i = 0; i < facts.size(); i++) {
                Map<String, Object> fact = facts.get(i);
                UUID factId = (UUID) fact.get("id");
                writeToolRepository.invalidateFact(factId);
                Map<String, Object> invalidatePayload = new java.util.LinkedHashMap<>();
                invalidatePayload.put("fact_id", factId.toString());
                pushDispatcher.dispatch(opLogWriter.append("kg_invalidate", invalidatePayload));

                String predicate = (String) fact.get("predicate");
                String object = (String) fact.get("object");
                Map<String, Object> inserted = writeToolRepository.addFact(
                        canonical, predicate, object,
                        ((Number) fact.get("confidence")).doubleValue(),
                        (UUID) fact.get("source_id"),
                        (String) fact.get("status"),
                        (String) fact.get("agent_id"),
                        (OffsetDateTime) fact.get("valid_from"),
                        embeddings.get(i));
                Map<String, Object> addPayload = new java.util.LinkedHashMap<>();
                addPayload.put("fact_id", inserted.get("id"));
                addPayload.put("subject", canonical);
                addPayload.put("predicate", predicate);
                addPayload.put("object", object);
                addPayload.put("confidence", fact.get("confidence"));
                addPayload.put("source_id", fact.get("source_id") == null ? null : fact.get("source_id").toString());
                addPayload.put("status", fact.get("status"));
                addPayload.put("agent_id", fact.get("agent_id"));
                addPayload.put("valid_from", fact.get("valid_from") == null ? null : fact.get("valid_from").toString());
                pushDispatcher.dispatch(opLogWriter.append("kg_add", addPayload));
            }

            int resultingConflicts = writeToolRepository.countCanonicalConflicts(canonical);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("registered", true);
            result.put("migrated", facts.size());
            result.put("resulting_conflicts", resultingConflicts);
            return result;
        });
    }

    @Transactional
    public Map<String, Object> kgInvalidate(UUID factId) {
        int updated = writeToolRepository.invalidateFact(factId);
        if (updated == 0) {
            return Map.of("invalidated", false);
        }
        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("fact_id", factId.toString());
        UUID opId = opLogWriter.append("kg_invalidate", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("invalidated", true);
    }

    @Transactional
    public Map<String, Object> reviseFact(AuthPrincipal principal, UUID oldId, String newObject) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : STATUS_COMMITTED;

        List<Float> factEmbedding = null;
        try {
            Optional<String[]> subjectPredicate = writeToolRepository.findFactSubjectPredicate(oldId);
            if (subjectPredicate.isPresent()) {
                String[] sp = subjectPredicate.get();
                factEmbedding = embeddingClient.encodeDocument(sp[0] + " " + sp[1] + " " + newObject);
            }
        } catch (RuntimeException e) {
            log.warn("Fact embedding unavailable, storing without embedding", e);
        }

        Map<String, Object> result = writeToolRepository.reviseFact(oldId, newObject, principal.name(), status, factEmbedding);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("fact_id", oldId.toString());
        opPayload.put("new_fact_id", result.get("new_id").toString());
        opPayload.put("new_object", newObject);
        opPayload.put("agent_id", principal.name());
        opPayload.put("status", status);
        UUID opId = opLogWriter.append("revise_fact", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> reviseCell(AuthPrincipal principal, UUID oldId, String newContent, String newSummary) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : STATUS_COMMITTED;
        List<Float> embedding = embeddingClient.encodeForCell(newContent, newSummary);
        Map<String, Object> result = writeToolRepository.reviseCell(oldId, newContent, newSummary, embedding, principal.name(), status);

        if (NeedsSummaryDecider.needsSummary(newContent, newSummary)) {
            Object newIdObj = result.get("new_id");
            if (newIdObj != null) {
                UUID newId = UUID.fromString(newIdObj.toString());
                writeToolRepository.tagNeedsSummary(newId);
                eventPublisher.publishEvent(new CellNeedsSummaryEvent(newId));
            }
        }

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", oldId.toString());
        opPayload.put("new_cell_id", result.get("new_id").toString());
        opPayload.put("new_content", newContent);
        opPayload.put("new_summary", newSummary);
        opPayload.put("agent_id", principal.name());
        opPayload.put("status", status);
        UUID opId = opLogWriter.append("revise_cell", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    /**
     * Revise a cell, persisting LLM-derived metadata (key_points, insight, tags) alongside the
     * summary. Used by the summarizer; unlike {@link #reviseCell}, it does not re-tag needs_summary
     * (the caller is expected to pass a real summary and to manage that tag explicitly).
     */
    @Transactional
    public Map<String, Object> reviseCellWithSummary(
            AuthPrincipal principal,
            UUID oldId,
            String newContent,
            String newSummary,
            List<String> keyPoints,
            String insight,
            List<String> tags
    ) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : STATUS_COMMITTED;
        List<Float> embedding = embeddingClient.encodeForCell(newContent, newSummary);
        Map<String, Object> result = writeToolRepository.reviseCell(
                oldId, newContent, newSummary, keyPoints, insight, tags, embedding, principal.name(), status);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", oldId.toString());
        opPayload.put("new_cell_id", result.get("new_id").toString());
        opPayload.put("new_content", newContent);
        opPayload.put("new_summary", newSummary);
        // Ship the LLM-derived enrichment so peers replay it too (OpReplayer prefers these over
        // the old revision's values); without them peers only ever see the bare summary.
        opPayload.put("new_key_points", keyPoints);
        opPayload.put("new_insight", insight);
        opPayload.put("new_tags", tags);
        opPayload.put("agent_id", principal.name());
        opPayload.put("status", status);
        UUID opId = opLogWriter.append("revise_cell", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    /**
     * Update derived cell metadata (document_type, topic/title, valid_from, extra tags) on the
     * current revision, op-logged as {@code update_cell_meta} so the change replicates to peers.
     * Used by the summarizer, whose enrichment previously bypassed the op log entirely. Null
     * arguments leave the corresponding field unchanged.
     */
    @Transactional
    public Map<String, Object> updateCellMeta(
            AuthPrincipal principal,
            UUID cellId,
            String documentType,
            String topic,
            OffsetDateTime validFrom,
            List<String> addTagsList
    ) {
        if (documentType == null && topic == null && validFrom == null
                && (addTagsList == null || addTagsList.isEmpty())) {
            return Map.of("updated", 0);
        }
        int updated = writeToolRepository.updateCellMeta(cellId, documentType, topic, validFrom, addTagsList);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("document_type", documentType);
        opPayload.put("topic", topic);
        opPayload.put("valid_from", validFrom == null ? null : validFrom.toString());
        opPayload.put("add_tags", addTagsList);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("update_cell_meta", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("updated", updated);
    }

    @Transactional
    public Map<String, Object> reclassifyCell(
            AuthPrincipal principal,
            UUID cellId,
            String realm,
            String topic,
            String signal
    ) {
        if (realm == null && topic == null && signal == null) {
            throw new IllegalArgumentException("at least one of realm/topic/signal required");
        }
        if (signal != null
                && !signal.equals(SIGNAL_FACTS)
                && !signal.equals(SIGNAL_EVENTS)
                && !signal.equals(SIGNAL_DISCOVERIES)
                && !signal.equals(SIGNAL_PREFERENCES)
                && !signal.equals(SIGNAL_ADVICE)) {
            throw new IllegalArgumentException(
                    "signal must be one of facts/events/discoveries/preferences/advice");
        }
        String normalizedRealm = realm == null ? null : normalizeClassification(realm, "realm");
        String normalizedTopic = topic == null ? null : normalizeClassification(topic, "topic");
        Map<String, Object> result = writeToolRepository.reclassifyCell(cellId, normalizedRealm, normalizedTopic, signal);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("new_realm", normalizedRealm);
        opPayload.put("new_topic", normalizedTopic);
        opPayload.put("new_signal", signal);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("reclassify_cell", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> rejectCell(AuthPrincipal principal, UUID cellId, String reason) {
        Map<String, Object> result = writeToolRepository.rejectCell(cellId);
        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("reason", reason);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("reject_cell", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> addTags(AuthPrincipal principal, UUID cellId, List<String> tags) {
        int updated = writeToolRepository.addTags(cellId, tags);
        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("tags", tags);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("add_tags", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("updated", updated);
    }

    @Transactional
    public Map<String, Object> removeTags(AuthPrincipal principal, UUID cellId, List<String> tags) {
        int updated = writeToolRepository.removeTags(cellId, tags);
        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("tags", tags);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("remove_tags", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("updated", updated);
    }

    @Transactional
    public Map<String, Object> bulkTag(AuthPrincipal principal, List<UUID> cellIds, List<String> addTagsList, List<String> removeTagsList) {
        int updated = 0;
        for (UUID id : cellIds) {
            int rowsAffected = 0;
            if (addTagsList != null && !addTagsList.isEmpty()) {
                rowsAffected = Math.max(rowsAffected, writeToolRepository.addTags(id, addTagsList));
            }
            if (removeTagsList != null && !removeTagsList.isEmpty()) {
                rowsAffected = Math.max(rowsAffected, writeToolRepository.removeTags(id, removeTagsList));
            }
            updated += rowsAffected;
        }
        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_ids", cellIds.stream().map(UUID::toString).toList());
        opPayload.put("add_tags", addTagsList);
        opPayload.put("remove_tags", removeTagsList);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("bulk_tag", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("updated", updated, "matched", cellIds.size());
    }

    @Transactional
    public Map<String, Object> bulkReclassify(AuthPrincipal principal, List<UUID> cellIds, String realm, String signal, String topic) {
        int updated = 0;
        for (UUID id : cellIds) {
            reclassifyCell(principal, id, realm, topic, signal);
            updated++;
        }
        return Map.of("updated", updated, "matched", cellIds.size());
    }

    @Transactional
    public Map<String, Object> bulkTagBySelector(AuthPrincipal principal, CellSelector selector,
            List<String> addTagsList, List<String> removeTagsList, boolean confirm) {
        List<UUID> ids = resolveSelector(selector, confirm);
        Map<String, Object> inner = bulkTag(principal, ids, addTagsList, removeTagsList);
        return Map.of("updated", inner.get("updated"), "matched", ids.size());
    }

    @Transactional
    public Map<String, Object> bulkReclassifyBySelector(AuthPrincipal principal, CellSelector selector,
            String realm, String signal, String topic, boolean confirm) {
        List<UUID> ids = resolveSelector(selector, confirm);
        Map<String, Object> inner = bulkReclassify(principal, ids, realm, signal, topic);
        return Map.of("updated", inner.get("updated"), "matched", ids.size());
    }

    private List<UUID> resolveSelector(CellSelector selector, boolean confirm) {
        List<UUID> ids = cellSelectorRepository.selectAllIds(selector, BULK_SELECTOR_CAP + 1);
        if (ids.size() > BULK_SELECTOR_CAP) {
            throw new IllegalArgumentException(
                    "where matches more than " + BULK_SELECTOR_CAP + " cells — narrow the selector");
        }
        if (ids.size() > BULK_CONFIRM_THRESHOLD && !confirm) {
            throw new IllegalArgumentException("where matches " + ids.size()
                    + " cells (> " + BULK_CONFIRM_THRESHOLD + ") — pass confirm: true to proceed");
        }
        return ids;
    }

    private static String normalizeClassification(String value, String field) {
        String normalized = value.strip().toLowerCase().replace(' ', '-');
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return normalized;
    }

    private static final String SIGNAL_FACTS = "facts";
    private static final String SIGNAL_EVENTS = "events";
    private static final String SIGNAL_DISCOVERIES = "discoveries";
    private static final String SIGNAL_PREFERENCES = "preferences";
    private static final String SIGNAL_ADVICE = "advice";

    @Transactional
    public Map<String, Object> updateIdentity(String key, String content) {
        int tokenCount = content.length() / 4;
        writeToolRepository.upsertIdentity(key, content, tokenCount);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("key", key);
        opPayload.put("content", content);
        opPayload.put("token_count", tokenCount);
        UUID opId = opLogWriter.append("update_identity", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("key", key, "token_count", tokenCount);
    }

    @Transactional
    public Map<String, Object> addReference(
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            List<String> tags,
            Integer importance
    ) {
        String effectiveStatus = status == null ? "read" : status;
        Map<String, Object> result = writeToolRepository.addReference(
                title, url, author, refType, effectiveStatus, notes, tags, importance);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("reference_id", result.get("id"));
        opPayload.put("title", title);
        opPayload.put("url", url);
        opPayload.put("author", author);
        opPayload.put("ref_type", refType);
        opPayload.put("status", effectiveStatus);
        opPayload.put("notes", notes);
        opPayload.put("tags", tags);
        opPayload.put("importance", importance);
        UUID opId = opLogWriter.append("add_reference", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> linkReference(UUID cellId, UUID referenceId, String relation) {
        Map<String, Object> result = writeToolRepository.linkReference(cellId, referenceId, relation);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("cell_id", cellId.toString());
        opPayload.put("reference_id", referenceId.toString());
        opPayload.put("relation", relation);
        UUID opId = opLogWriter.append("link_reference", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> registerAgent(
            String name,
            String focus,
            String autonomyJson,
            String schedule,
            String modelRoutingJson,
            List<String> tools
    ) {
        Map<String, Object> result = writeToolRepository.registerAgent(name, focus, autonomyJson, schedule, modelRoutingJson, tools);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("agent_id", result.get("id"));
        opPayload.put("name", name);
        opPayload.put("focus", focus);
        opPayload.put("autonomy", autonomyJson);
        opPayload.put("schedule", schedule);
        opPayload.put("model_routing", modelRoutingJson);
        opPayload.put("tools", tools);
        UUID opId = opLogWriter.append("register_agent", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> diaryWrite(String agent, String entry) {
        Map<String, Object> result = writeToolRepository.diaryWrite(agent, entry);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("entry_id", result.get("id"));
        opPayload.put("agent", agent);
        opPayload.put("entry", entry);
        UUID opId = opLogWriter.append("diary_write", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> updateBlueprint(
            AuthPrincipal principal,
            String realm,
            String title,
            String narrative,
            List<String> signalOrder,
            List<UUID> keyCells
    ) {
        Map<String, Object> result = writeToolRepository.updateBlueprint(
                principal.name(), realm, title, narrative, signalOrder, keyCells);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("blueprint_id", result.get("id"));
        opPayload.put("realm", realm);
        opPayload.put("title", title);
        opPayload.put("narrative", narrative);
        opPayload.put("signal_order", signalOrder);
        opPayload.put("key_cells", keyCells == null ? null
                : keyCells.stream().map(UUID::toString).toList());
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("update_blueprint", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> addTunnel(
            AuthPrincipal principal,
            UUID fromCell,
            UUID toCell,
            String relation,
            String note,
            String requestedStatus
    ) {
        String status = principal.role() == AuthRole.AGENT ? STATUS_PENDING : effectiveStatus(principal.role(), requestedStatus);
        Map<String, Object> result = writeToolRepository.addTunnel(fromCell, toCell, relation, note, status, principal.name());

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("tunnel_id", result.get("id"));
        opPayload.put("from_cell_id", fromCell.toString());
        opPayload.put("to_cell_id", toCell.toString());
        opPayload.put("relation", relation);
        opPayload.put("note", note);
        opPayload.put("status", status);
        opPayload.put("agent_id", principal.name());
        UUID opId = opLogWriter.append("add_tunnel", opPayload);
        pushDispatcher.dispatch(opId);
        return result;
    }

    @Transactional
    public Map<String, Object> removeTunnel(UUID tunnelId) {
        writeToolRepository.removeTunnel(tunnelId);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("tunnel_id", tunnelId.toString());
        UUID opId = opLogWriter.append("remove_tunnel", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("id", tunnelId.toString(), "removed", true);
    }

    @Transactional
    public Map<String, Object> approvePending(List<UUID> ids, String decision) {
        int count = writeToolRepository.approvePending(ids, decision);

        Map<String, Object> opPayload = new java.util.LinkedHashMap<>();
        opPayload.put("ids", ids.stream().map(UUID::toString).toList());
        opPayload.put("decision", decision);
        opPayload.put("count", count);
        UUID opId = opLogWriter.append("approve_pending", opPayload);
        pushDispatcher.dispatch(opId);
        return Map.of("decision", decision, "count", count);
    }

    private static String effectiveStatus(AuthRole role, String requestedStatus) {
        if (role == AuthRole.AGENT) {
            return STATUS_PENDING;
        }
        if (requestedStatus == null) {
            return STATUS_COMMITTED;
        }
        return switch (requestedStatus) {
            case STATUS_PENDING, STATUS_COMMITTED, STATUS_REJECTED -> requestedStatus;
            default -> throw new IllegalArgumentException("Invalid status");
        };
    }
}
