package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(50)
public class KgRenamePredicateToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public KgRenamePredicateToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "kg_rename_predicate";
    }

    @Override
    public String description() {
        return "Rename a KG predicate: invalidates every active fact with predicate 'from' and re-adds it "
             + "under predicate 'to', preserving subject, object, confidence, source, status and valid_from. "
             + "Optional subject filter. Requires confirm: true above 200 matched facts; hard cap 1000.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("from", "Predicate to rename")
                .requiredString("to", "New predicate name")
                .optionalString("subject", "Only rename facts of this exact subject")
                .optionalBoolean("confirm", "Required true when more than 200 facts match")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String from = WriteArgumentParser.requiredText(arguments, "from");
        String to = WriteArgumentParser.requiredText(arguments, "to");
        String subject = WriteArgumentParser.optionalText(arguments, "subject");
        boolean confirm = arguments != null && arguments.path("confirm").asBoolean(false);
        return writeToolService.kgRenamePredicate(principal, from, to, subject, confirm);
    }
}
