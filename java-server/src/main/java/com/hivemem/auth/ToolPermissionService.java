package com.hivemem.auth;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
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
            "quick_facts",
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
            "list_saved_searches",
            "list_cell_ids",
            "entity_overview",
            "blueprints_missing"
    );

    private static final Set<String> WRITE_TOOLS = tools(
            "add_cell",
            "add_tunnel",
            "kg_add",
            "kg_invalidate",
            "kg_rename_predicate",
            "update_identity",
            "add_reference",
            "link_reference",
            "remove_tunnel",
            "revise_cell",
            "reclassify_cell",
            "revise_fact",
            "register_agent",
            "diary_write",
            "update_blueprint",
            "upload_attachment",
            "save_search",
            "delete_saved_search",
            "add_tags",
            "remove_tags",
            "bulk_tag",
            "bulk_reclassify"
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
}
