package run.halo.mcpserver.tools;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/** One built-in MCP tool with Console-specific presentation metadata. */
public record BuiltInTool(
        McpStatelessServerFeatures.AsyncToolSpecification specification,
        String category,
        String displayTitle,
        String displayDescription) {

    public McpSchema.Tool protocolTool() {
        return specification.tool();
    }
}
