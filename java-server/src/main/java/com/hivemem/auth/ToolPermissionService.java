package com.hivemem.auth;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ToolPermissionService {

    private static Set<String> tools(String... toolNames) {
        return Set.of(toolNames);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> combined = new HashSet<>(first);
        combined.addAll(second);
        return Set.copyOf(combined);
    }

    private static final Set<String> READ_TOOLS = tools(
            "status",
            "search",
            "search_kg",
            "get_cell",
            "list",
            "traverse",
            "time_machine",
            "wake_up",
            "history",
            "pending_approvals",
            "get_blueprint",
            "reading_list",
            "list_agents",
            "diary_read",
            "list_attachments",
            "get_attachment_info",
            "facet_count",
            "list_documents",
            "list_media",
            "list_cell_ids",
            "entity_overview",
            "blueprints_missing",
            "data_quality_report"
    );

    private static final Set<String> WRITE_TOOLS = tools(
            "add_cell",
            "add_tunnel",
            "kg_add",
            "kg_invalidate",
            "kg_rename_predicate",
            "kg_alias",
            "update_identity",
            "add_reference",
            "link_reference",
            "remove_tunnel",
            "revise_cell",
            "reclassify",
            "reject_cell",
            "revise_fact",
            "register_agent",
            "diary_write",
            "update_blueprint",
            "upload_attachment",
            "saved_searches",
            "manage_tags"
    );

    private static final Set<String> ADMIN_TOOLS = tools(
            "approve_pending",
            "health",
            "queen_runs",
            "queen_run_detail"
    );

    private static final Set<String> WRITER_TOOLS = union(READ_TOOLS, WRITE_TOOLS);
    private static final Set<String> AGENT_TOOLS = WRITER_TOOLS;
    private static final Set<String> ALL_TOOLS = union(WRITER_TOOLS, ADMIN_TOOLS);

    private static final Map<AuthRole, Set<String>> ROLE_TOOLS = Map.of(
            AuthRole.ADMIN, ALL_TOOLS,
            AuthRole.WRITER, WRITER_TOOLS,
            AuthRole.READER, READ_TOOLS,
            AuthRole.AGENT, AGENT_TOOLS
    );

    public boolean isAllowed(AuthRole role, String toolName) {
        return allowedTools(role).contains(toolName);
    }

    public Set<String> allowedTools(AuthRole role) {
        if (role == null) {
            return Set.of();
        }
        return ROLE_TOOLS.getOrDefault(role, Set.of());
    }

    // --- realm ACL classification (scoped tokens only) ---

    /** id/selector writes + fail-closed unknowns: always denied for a realm-scoped token. */
    private static final Set<String> WRITE_DENY_WHEN_SCOPED = tools(
            "reclassify", "revise_cell", "manage_tags", "reject_cell", "register_agent");

    /** Realm-less knowledge-graph / global writes: allowed for scoped tokens as-is. */
    private static final Set<String> KG_GLOBAL_WRITES = tools(
            "kg_add", "add_tunnel", "add_reference", "link_reference", "remove_tunnel",
            "kg_invalidate", "revise_fact", "kg_rename_predicate", "kg_alias", "update_identity",
            "diary_write", "saved_searches");

    /** Writes that carry an explicit `realm` argument checked against write_realms. */
    private static final Set<String> WRITE_REALM_ARG_TOOLS = tools(
            "add_cell", "update_blueprint", "upload_attachment");

    /** Reads whose response is filtered post-hoc (rows/objects dropped by `realm`). */
    private static final Set<String> READ_RESPONSE_FILTER_TOOLS = tools(
            "search", "get_cell", "get_blueprint");

    /** Reads that take an optional `realm` drill-down arg, pre-checked/rewritten. */
    private static final Set<String> READ_ARG_PRECHECK_TOOLS = tools(
            "list", "facet_count", "list_cell_ids");

    /** Realm-less reads: always allowed for scoped tokens. */
    private static final Set<String> READ_GLOBAL_TOOLS = tools(
            "search_kg", "time_machine", "wake_up");

    /** Reads whose response carries no filterable realm, or is realm-mixed: denied when scoped. */
    private static final Set<String> READ_DENY_WHEN_SCOPED = tools(
            "traverse", "history", "entity_overview", "data_quality_report", "pending_approvals",
            "reading_list", "list_documents", "list_media", "list_agents", "diary_read",
            "list_attachments", "get_attachment_info", "blueprints_missing");

    /**
     * Reads whose realm filter is expressed via a {@code where.realm_in} selector, into which
     * read_realms is injected before the handler runs (see {@link #rewriteReadArgs}). Foreign-row
     * dropping in {@link #filterReadResponse} is the enforced backstop.
     */
    private static final Set<String> READ_REALM_IN_INJECT_TOOLS = tools(
            "search", "facet_count", "list_cell_ids");

    /**
     * Per-tool flat filter params that are folded into a {@code where} object when read_realms is
     * injected (the handlers treat {@code where} and flat params as mutually exclusive, so an
     * injected {@code where.realm_in} would otherwise collide with a caller-supplied flat filter).
     * {@code query} is folded for facet_count (its {@code where} supports it) but NOT for search
     * (search keeps {@code query} top-level and rejects {@code where.query}).
     */
    private static final Map<String, Set<String>> FLAT_FILTER_KEYS = Map.of(
            "search", Set.of("realm", "signal", "topic", "tags", "status"),
            "facet_count", Set.of("realm", "signal", "topic", "tags", "status", "query"),
            "list_cell_ids", Set.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Deny reason for a scoped token, or empty if the call is allowed. No-op for unscoped tokens. */
    public Optional<String> realmDenial(AuthPrincipal principal, String toolName, JsonNode arguments) {
        if (principal == null || !principal.isRealmScoped()) {
            return Optional.empty();
        }

        if (WRITE_TOOLS.contains(toolName)) {
            return writeRealmDenial(principal, toolName, arguments);
        }
        if (READ_TOOLS.contains(toolName)) {
            return readRealmDenial(principal, toolName, arguments);
        }
        // ADMIN tools are already gated by isAllowed(); nothing further to say here.
        return Optional.empty();
    }

    private Optional<String> writeRealmDenial(AuthPrincipal principal, String toolName, JsonNode arguments) {
        if (principal.writeRealms() == null) {
            return Optional.empty(); // write-unrestricted (only read-scoped)
        }
        if (KG_GLOBAL_WRITES.contains(toolName)) {
            return Optional.empty();
        }
        if (WRITE_REALM_ARG_TOOLS.contains(toolName)) {
            List<String> writeRealms = principal.writeRealms();
            JsonNode realm = arguments.path("realm");
            if (realm.isMissingNode() || realm.asText("").isBlank()) {
                // A realm-scoped write MUST name an explicit realm: the write path (e.g.
                // WriteToolService.addCell) persists a null-realm cell when realm is omitted, so
                // a missing realm would escape write_realms entirely. Fail closed — no
                // "single write realm defaults it" shortcut.
                return Optional.of("write realm required for scoped token");
            }
            String realmValue = realm.asText();
            return writeRealms.contains(realmValue)
                    ? Optional.empty()
                    : Optional.of("write to realm '" + realmValue + "' not permitted");
        }
        if (WRITE_DENY_WHEN_SCOPED.contains(toolName)) {
            return Optional.of("write tool '" + toolName + "' not permitted for realm-scoped token");
        }
        // any other WRITE tool → default-deny
        return Optional.of("write tool '" + toolName + "' not permitted for realm-scoped token");
    }

    private Optional<String> readRealmDenial(AuthPrincipal principal, String toolName, JsonNode arguments) {
        if (principal.readRealms() == null) {
            return Optional.empty(); // read-unrestricted (only write-scoped)
        }
        if (READ_GLOBAL_TOOLS.contains(toolName)) {
            return Optional.empty();
        }
        Set<String> allowed = Set.copyOf(principal.readRealms());
        // Named-realm precheck (fail-closed): any realm the caller explicitly names — top-level
        // `realm`, `where.realm`, or any element of `where.realm_in` — that is not in read_realms
        // is denied outright. This covers every read tool that carries a realm/where selector
        // (list drilldown, facet_count, list_cell_ids, search) so a `where.realm=personal` can
        // never slip through unfiltered. After this passes, every caller-named realm ⊆ read_realms.
        Optional<String> forbidden = firstForbiddenRealm(arguments, allowed);
        if (forbidden.isPresent()) {
            return Optional.of("realm '" + forbidden.get() + "' not visible");
        }
        if (READ_RESPONSE_FILTER_TOOLS.contains(toolName)) {
            return Optional.empty(); // read_realms injected (rewriteReadArgs) + filtered (filterReadResponse)
        }
        if (READ_ARG_PRECHECK_TOOLS.contains(toolName) || toolName.equals("status")) {
            return Optional.empty(); // enumeration handled by rewrite + filter
        }
        if (READ_DENY_WHEN_SCOPED.contains(toolName)) {
            return Optional.of("read tool '" + toolName + "' not permitted for realm-scoped token");
        }
        // fail-closed: any READ tool not covered by an explicit allow bucket is denied
        return Optional.of("read tool '" + toolName + "' not permitted for realm-scoped token");
    }

    /**
     * First realm named by the caller (top-level {@code realm}, {@code where.realm}, or any
     * {@code where.realm_in} element) that is NOT in {@code allowed}, or empty if every named
     * realm is visible (or none is named).
     */
    private static Optional<String> firstForbiddenRealm(JsonNode arguments, Set<String> allowed) {
        if (arguments == null) {
            return Optional.empty();
        }
        JsonNode topRealm = arguments.path("realm");
        if (topRealm.isTextual() && !topRealm.asText().isBlank() && !allowed.contains(topRealm.asText())) {
            return Optional.of(topRealm.asText());
        }
        JsonNode where = arguments.path("where");
        if (where.isObject()) {
            JsonNode whereRealm = where.path("realm");
            if (whereRealm.isTextual() && !whereRealm.asText().isBlank()
                    && !allowed.contains(whereRealm.asText())) {
                return Optional.of(whereRealm.asText());
            }
            JsonNode realmIn = where.path("realm_in");
            if (realmIn.isArray()) {
                for (JsonNode element : realmIn) {
                    if (element.isTextual() && !allowed.contains(element.asText())) {
                        return Optional.of(element.asText());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** True when the caller has explicitly named any realm (top-level or via where). */
    private static boolean namesAnyRealm(JsonNode arguments) {
        if (arguments == null) {
            return false;
        }
        JsonNode topRealm = arguments.path("realm");
        if (topRealm.isTextual() && !topRealm.asText().isBlank()) {
            return true;
        }
        JsonNode where = arguments.path("where");
        if (where.isObject()) {
            JsonNode whereRealm = where.path("realm");
            if (whereRealm.isTextual() && !whereRealm.asText().isBlank()) {
                return true;
            }
            JsonNode realmIn = where.path("realm_in");
            if (realmIn.isArray() && !realmIn.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject read_realms into the realm filter of enumerating reads so the DB returns only visible
     * rows (otherwise {@code search}/{@code list_cell_ids} fill their {@code limit} with foreign
     * rows that {@link #filterReadResponse} then drops → under-filled pages). No-op for unscoped.
     *
     * <p>When the caller already named a realm (guaranteed ⊆ read_realms by the {@code realmDenial}
     * precheck) the args are left untouched — the DB already restricts to that visible subset.
     * When no realm is named, {@code where.realm_in = read_realms} is injected, folding any flat
     * filter params into the {@code where} object to respect the handler's where/flat exclusivity.
     * {@code list} is not injectable (its single {@code realm} arg selects the enumeration level),
     * so it relies solely on {@link #filterReadResponse}.
     */
    public JsonNode rewriteReadArgs(AuthPrincipal principal, String toolName, JsonNode arguments) {
        if (principal == null || principal.readRealms() == null) {
            return arguments;
        }
        if (!READ_REALM_IN_INJECT_TOOLS.contains(toolName)) {
            return arguments;
        }
        if (namesAnyRealm(arguments)) {
            // Caller pinned realm(s); precheck guaranteed they are visible. DB already filters.
            return arguments == null ? null : arguments.deepCopy();
        }
        ObjectNode root = (arguments != null && arguments.isObject())
                ? (ObjectNode) arguments.deepCopy()
                : MAPPER.createObjectNode();
        ObjectNode where = (root.path("where").isObject())
                ? (ObjectNode) root.get("where")
                : MAPPER.createObjectNode();
        // Fold flat filter params into `where` so the injected where.realm_in doesn't collide with
        // the handler's where/flat mutual-exclusivity check.
        for (String key : FLAT_FILTER_KEYS.getOrDefault(toolName, Set.of())) {
            if (root.has(key) && !root.get(key).isNull()) {
                where.set(key, root.get(key));
                root.remove(key);
            }
        }
        ArrayNode realmIn = MAPPER.createArrayNode();
        for (String realm : principal.readRealms()) {
            realmIn.add(realm);
        }
        where.set("realm_in", realmIn);
        root.set("where", where);
        return root;
    }

    /** Drop foreign-realm rows / realm-name leaks from a read response. No-op for unscoped. */
    public JsonNode filterReadResponse(AuthPrincipal principal, String toolName,
                                       JsonNode arguments, JsonNode resultTree) {
        if (principal == null || principal.readRealms() == null) {
            return resultTree;
        }
        Set<String> allowed = Set.copyOf(principal.readRealms());

        if (READ_RESPONSE_FILTER_TOOLS.contains(toolName)) {
            if (resultTree.isArray()) {
                ArrayNode kept = MAPPER.createArrayNode();
                for (JsonNode row : resultTree) {
                    JsonNode r = row.path("realm");
                    if (r.isMissingNode() || allowed.contains(r.asText())) {
                        kept.add(row);
                    }
                }
                return kept;
            }
            // single object (get_cell/get_blueprint): foreign realm → null node
            JsonNode r = resultTree.path("realm");
            if (!r.isMissingNode() && !allowed.contains(r.asText())) {
                return MAPPER.nullNode();
            }
            return resultTree;
        }

        if (toolName.equals("status")) {
            ObjectNode copy = (ObjectNode) resultTree.deepCopy();
            if (copy.path("realms").isArray()) {
                copy.set("realms", filterRealmArray(copy.get("realms"), allowed));
            }
            // Realm-bearing counts (cells/pending) cannot be faithfully recomputed by a pure
            // filter from the already-aggregated snapshot; v1-acceptable fallback (documented in
            // the task brief, M1 open point): filter only the `realms` list (no realm-name leak)
            // and leave numeric counts as global volume. Revisit in the status handler if
            // per-realm counts become available upstream.
            return copy;
        }

        if (toolName.equals("list")) {
            // Only the no-arg realm enumeration (listRealms → [{value,label,cell_count}]) leaks
            // foreign realm names. A `list realm=X` drilldown is prechecked (X ⊆ read_realms) and
            // returns signals/topics/cells whose `value` are NOT realm names — must not be filtered.
            if (!namesAnyRealm(arguments) && resultTree.isArray()) {
                return filterRealmArray(resultTree, allowed);
            }
            return resultTree;
        }

        if (toolName.equals("facet_count")) {
            // {"<field>": [{value,count}, ...], ...}. Only the `realm` facet enumerates realm
            // names; other facets (tag/status/year/signal/fact:*) are already restricted to
            // visible realms by the injected where.realm_in and must not be value-filtered.
            if (resultTree.isObject() && resultTree.path("realm").isArray()) {
                ObjectNode copy = (ObjectNode) resultTree.deepCopy();
                copy.set("realm", filterRealmArray(copy.get("realm"), allowed));
                return copy;
            }
            return resultTree;
        }

        if (toolName.equals("list_cell_ids")) {
            // {"ids":[{id,realm,signal,topic}], "total":N}. Drop rows outside read_realms (incl.
            // null-realm rows) as a backstop to the injected where.realm_in.
            if (resultTree.isObject() && resultTree.path("ids").isArray()) {
                ObjectNode copy = (ObjectNode) resultTree.deepCopy();
                ArrayNode kept = MAPPER.createArrayNode();
                for (JsonNode row : copy.get("ids")) {
                    JsonNode r = row.path("realm");
                    if (r.isTextual() && allowed.contains(r.asText())) {
                        kept.add(row);
                    }
                }
                copy.set("ids", kept);
                return copy;
            }
            return resultTree;
        }

        // other allowed buckets (READ_GLOBAL_TOOLS) carry no realm-filterable rows.
        return resultTree;
    }

    /** Keep only array elements whose realm (object {@code value} or bare string) is visible. */
    private static ArrayNode filterRealmArray(JsonNode array, Set<String> allowed) {
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode element : array) {
            String value = element.isObject() ? element.path("value").asText("") : element.asText();
            if (allowed.contains(value)) {
                kept.add(element);
            }
        }
        return kept;
    }
}
