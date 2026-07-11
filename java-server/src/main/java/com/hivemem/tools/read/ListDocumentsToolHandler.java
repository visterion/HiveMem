package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(16)
public class ListDocumentsToolHandler implements ToolHandler {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ReadToolService readToolService;

    public ListDocumentsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "Browse documents in a realm (no query) with tag/status filters, sort, and paging; " +
               "joins the extraction-source attachment.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("realm", "Realm to browse (default: documents). Pass \"none\" to list cells with no realm assigned (realm IS NULL).")
                .optionalString("signal", "Restrict to this signal")
                .optionalString("topic", "Restrict to this topic")
                .optionalStringList("tags", "Filter to documents that have ANY of the given tags")
                .optionalString("status", "Restrict to a status: committed | pending | rejected | all (default committed; " +
                        "\"all\" bypasses the status filter entirely)")
                .optionalEnumString("sort", "Sort order (default newest)", "newest", "oldest", "title")
                .optionalInteger("limit", "Maximum rows to return (default 50, max 200)")
                .optionalInteger("offset", "Number of rows to skip (default 0)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm  = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic  = WriteArgumentParser.optionalText(arguments, "topic");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        String sort   = WriteArgumentParser.optionalText(arguments, "sort");

        List<String> tags = null;
        if (arguments != null && arguments.hasNonNull("tags") && arguments.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode element : arguments.get("tags")) {
                tags.add(element.asText());
            }
        }

        int limit = DEFAULT_LIMIT;
        Integer limitArg = WriteArgumentParser.optionalInteger(arguments, "limit");
        if (limitArg != null) {
            if (limitArg < 1 || limitArg > MAX_LIMIT) {
                throw new IllegalArgumentException("Invalid limit");
            }
            limit = limitArg;
        }

        int offset = 0;
        Integer offsetArg = WriteArgumentParser.optionalInteger(arguments, "offset");
        if (offsetArg != null) {
            if (offsetArg < 0) {
                throw new IllegalArgumentException("Invalid offset");
            }
            offset = offsetArg;
        }

        return readToolService.listDocuments(realm, signal, topic, tags, status, sort, limit, offset);
    }
}
