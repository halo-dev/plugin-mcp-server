package run.halo.mcpserver.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-serializable result returned by a contributed tool. */
public record McpToolResult(Map<String, Object> structuredContent, String textContent, boolean error) {

    public static McpToolResult success(Map<String, ?> structuredContent) {
        return new McpToolResult(new LinkedHashMap<>(structuredContent), null, false);
    }

    public static McpToolResult success(
            Map<String, ?> structuredContent, String textContent) {
        return new McpToolResult(new LinkedHashMap<>(structuredContent), textContent, false);
    }

    public static McpToolResult error(String code, String message) {
        var structured = Map.<String, Object>of(
                "error", Map.of("code", code, "message", message));
        return new McpToolResult(structured, code + ": " + message, true);
    }
}
