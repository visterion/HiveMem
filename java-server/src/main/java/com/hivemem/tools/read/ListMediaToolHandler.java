package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(46)
public class ListMediaToolHandler implements ToolHandler {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final ReadToolService readToolService;

    public ListMediaToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() { return "list_media"; }

    @Override
    public String description() {
        return "List image attachments for the photo gallery (Vision-classified photo_general or " +
               "whiteboard_photo) across realms, with EXIF metadata, sorted by capture date.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("realm", "Restrict to one realm (default: all realms)")
                .optionalEnumString("sort", "Sort order by capture date (default newest)", "newest", "oldest")
                .optionalInteger("limit", "Maximum rows to return (default 100, max 500)")
                .optionalInteger("offset", "Number of rows to skip (default 0)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String sort  = WriteArgumentParser.optionalText(arguments, "sort");

        int limit = DEFAULT_LIMIT;
        Integer limitArg = WriteArgumentParser.optionalInteger(arguments, "limit");
        if (limitArg != null) {
            if (limitArg < 1 || limitArg > MAX_LIMIT) throw new IllegalArgumentException("Invalid limit");
            limit = limitArg;
        }

        int offset = 0;
        Integer offsetArg = WriteArgumentParser.optionalInteger(arguments, "offset");
        if (offsetArg != null) {
            if (offsetArg < 0) throw new IllegalArgumentException("Invalid offset");
            offset = offsetArg;
        }

        return readToolService.listMedia(realm, sort, limit, offset);
    }
}
