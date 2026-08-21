package run.halo.mcpserver.api;

/** Stable, provider-controlled error that is returned as an MCP tool error. */
public class McpToolException extends RuntimeException {

    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpToolException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
