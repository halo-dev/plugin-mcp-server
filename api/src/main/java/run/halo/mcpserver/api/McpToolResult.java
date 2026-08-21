package run.halo.mcpserver.api;

import java.util.Map;

/** JSON-serializable result returned by a contributed tool. */
public record McpToolResult(Object structuredContent, String textContent, boolean error) {

    public static McpToolResult success(Object structuredContent) {
        return new McpToolResult(structuredContent, null, false);
    }

    public static McpToolResult success(Object structuredContent, String textContent) {
        return new McpToolResult(structuredContent, textContent, false);
    }

    public static McpToolResult error(String code, String message) {
        var structured = Map.of(
                "error", Map.of("code", code, "message", message));
        return new McpToolResult(structured, code + ": " + message, true);
    }
}
