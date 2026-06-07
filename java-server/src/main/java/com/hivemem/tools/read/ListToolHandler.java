package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(5)
public class ListToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "Navigate the Realm→Signal→Topic→Cell hierarchy. Omit all params for realms; add realm for signals; add realm+signal for topics; add realm+signal+topic for cells.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("realm", "Filter by realm")
                .optionalString("signal", "Filter by signal (requires realm)")
                .optionalString("topic", "Filter by topic (requires realm and signal)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = stringArg(arguments, "realm");
        String signal = stringArg(arguments, "signal");
        String topic = stringArg(arguments, "topic");

        if (signal != null && realm == null) {
            throw new IllegalArgumentException("signal requires realm");
        }
        if (topic != null && (realm == null || signal == null)) {
            throw new IllegalArgumentException("topic requires realm and signal");
        }

        if (realm == null) {
            return readToolService.listRealms();
        }
        if (signal == null) {
            return readToolService.listSignals(realm);
        }
        if (topic == null) {
            return readToolService.listTopics(realm, signal);
        }
        return readToolService.listCellsInTopic(realm, signal, topic);
    }

    private static String stringArg(JsonNode arguments, String name) {
        if (arguments == null || !arguments.hasNonNull(name)) {
            return null;
        }
        String value = arguments.get(name).asText();
        return value.isBlank() ? null : value;
    }
}
