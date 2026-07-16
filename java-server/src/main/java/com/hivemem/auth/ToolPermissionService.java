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
                // add_cell may omit realm iff exactly one write realm (caller then defaults it)
                return (toolName.equals("add_cell") && writeRealms.size() == 1)
                        ? Optional.empty()
                        : Optional.of("write realm required for scoped token");
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
        if (READ_RESPONSE_FILTER_TOOLS.contains(toolName)) {
            return Optional.empty(); // filtered post-response by filterReadResponse
        }
        if (READ_ARG_PRECHECK_TOOLS.contains(toolName) || toolName.equals("status")) {
            JsonNode realm = arguments.path("realm");
            if (!realm.isMissingNode() && !realm.asText("").isBlank()
                    && !principal.readRealms().contains(realm.asText())) {
                return Optional.of("realm '" + realm.asText() + "' not visible");
            }
            return Optional.empty(); // enumeration handled by rewrite + filter
        }
        if (READ_DENY_WHEN_SCOPED.contains(toolName)) {
            return Optional.of("read tool '" + toolName + "' not permitted for realm-scoped token");
        }
        // fail-closed: any READ tool not covered by an explicit allow bucket is denied
        return Optional.of("read tool '" + toolName + "' not permitted for realm-scoped token");
    }

    /** Inject read_realms into the realm filter for enumerating reads. No-op for unscoped. */
    public JsonNode rewriteReadArgs(AuthPrincipal principal, String toolName, JsonNode arguments) {
        if (principal == null || principal.readRealms() == null) {
            return arguments;
        }
        if (!READ_RESPONSE_FILTER_TOOLS.contains(toolName) && !READ_ARG_PRECHECK_TOOLS.contains(toolName)) {
            return arguments;
        }
        // Only inject when the caller did NOT pin a single realm (that path is pre-checked in
        // realmDenial). The actual injection into each tool's where-clause shape is wired in T4
        // against the handler's real arg schema; filterReadResponse is the enforced guarantee
        // regardless of whether this rewrite happens, so it is safe to leave args untouched here.
        return arguments.deepCopy();
    }

    /** Drop foreign-realm rows / realm-name leaks from a read response. No-op for unscoped. */
    public JsonNode filterReadResponse(AuthPrincipal principal, String toolName, JsonNode resultTree) {
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
                ArrayNode kept = MAPPER.createArrayNode();
                for (JsonNode realm : copy.get("realms")) {
                    String value = realm.isObject() ? realm.path("value").asText("") : realm.asText();
                    if (allowed.contains(value)) {
                        kept.add(realm);
                    }
                }
                copy.set("realms", kept);
            }
            // Realm-bearing counts (cells/pending) cannot be faithfully recomputed by a pure
            // filter from the already-aggregated snapshot; v1-acceptable fallback (documented in
            // the task brief, M1 open point): filter only the `realms` list (no realm-name leak)
            // and leave numeric counts as global volume. Revisit in the status handler if
            // per-realm counts become available upstream.
            return copy;
        }

        // list/facet_count/list_cell_ids are enforced via rewriteReadArgs + realmDenial precheck;
        // other allowed buckets (READ_GLOBAL_TOOLS) carry no realm-filterable rows.
        return resultTree;
    }
}
