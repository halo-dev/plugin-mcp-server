package run.halo.mcpserver.api;

/** MCP behavior hints kept as protocol-neutral values in the provider API. */
public record McpToolAnnotations(
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint,
        String title) {

    public static McpToolAnnotations readOnly(String title) {
        return new McpToolAnnotations(true, false, true, false, title);
    }

    public static McpToolAnnotations defaults(String title) {
        return new McpToolAnnotations(false, true, false, true, title);
    }
}
