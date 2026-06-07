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
@Order(29)
public class AddReferenceToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public AddReferenceToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "add_reference";
    }

    @Override
    public String description() {
        return "Add a source reference.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("title", "Title of the reference")
                .optionalString("url", "URL of the reference")
                .optionalString("author", "Author of the reference")
                .optionalEnumString("ref_type", "Reference type",
                        "article", "paper", "book", "video", "podcast", "tweet", "repo", "conversation", "internal", "other")
                .optionalEnumString("status", "Reading status (default: read)",
                        "unread", "reading", "read", "archived")
                .optionalString("notes", "Notes about the reference")
                .optionalStringList("tags", "Free-form tags")
                .optionalIntegerInRange("importance", "Importance score (1-5)", 1, 5)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String title = WriteArgumentParser.requiredText(arguments, "title");
        String url = WriteArgumentParser.optionalText(arguments, "url");
        String author = WriteArgumentParser.optionalText(arguments, "author");
        String refType = WriteArgumentParser.optionalText(arguments, "ref_type");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        String notes = WriteArgumentParser.optionalText(arguments, "notes");
        java.util.List<String> tags = WriteArgumentParser.optionalTextList(arguments, "tags");
        Integer importance = WriteArgumentParser.optionalInteger(arguments, "importance");
        return writeToolService.addReference(title, url, author, refType, status, notes, tags, importance);
    }
}
