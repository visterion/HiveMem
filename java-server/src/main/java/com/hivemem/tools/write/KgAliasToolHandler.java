package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(52)
public class KgAliasToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public KgAliasToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "kg_alias";
    }

    @Override
    public String description() {
        return "Register alias subjects for a canonical entity and retro-migrate existing active facts "
                + "onto the canonical subject (invalidate+re-add, valid_from preserved). Future kg_add "
                + "calls resolve these aliases automatically. Returns {registered, migrated, resulting_conflicts} "
                + "where resulting_conflicts is the count of active (subject,predicate) conflicts remaining on "
                + "the canonical after migration that still need supersede — including any pre-existing conflicts, "
                + "not only those caused by this operation. "
                + "Requires confirm: true above 200 matched facts; hard cap 1000.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("canonical", "Canonical entity name to keep")
                .requiredStringList("aliases", "Alias subject strings to fold into the canonical entity")
                .optionalBoolean("confirm", "Required true to migrate more than 200 facts")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String canonical = WriteArgumentParser.requiredText(arguments, "canonical");
        List<String> aliases = WriteArgumentParser.requiredTextList(arguments, "aliases");
        boolean confirm = arguments != null && arguments.path("confirm").asBoolean(false);
        return writeToolService.kgAlias(principal, canonical, aliases, confirm);
    }
}
