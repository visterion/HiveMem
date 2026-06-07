package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(19)
public class WakeUpToolHandler implements ToolHandler {

    private final ReadToolService readToolService;
    private final String defaultLanguage;

    public WakeUpToolHandler(ReadToolService readToolService,
                             @Value("${hivemem.language:de}") String defaultLanguage) {
        this.readToolService = readToolService;
        this.defaultLanguage = defaultLanguage;
    }

    @Override
    public String name() {
        return "wake_up";
    }

    @Override
    public String description() {
        return "Load identity context at session start.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("identity", principal.name());
        result.put("role", principal.role().name().toLowerCase(java.util.Locale.ROOT));
        result.put("default_language", defaultLanguage);
        result.put("context", readToolService.wakeUp());
        return result;
    }
}
