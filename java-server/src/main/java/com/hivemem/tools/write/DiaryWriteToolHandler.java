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
@Order(30)
public class DiaryWriteToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public DiaryWriteToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "diary_write";
    }

    @Override
    public String description() {
        return "Write an entry to an agent diary.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("agent", "Agent name to write the diary entry for")
                .requiredString("entry", "Diary entry content")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String agent = WriteArgumentParser.requiredText(arguments, "agent");
        String entry = WriteArgumentParser.requiredText(arguments, "entry");
        return writeToolService.diaryWrite(agent, entry);
    }
}
