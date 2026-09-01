package run.halo.mcpserver.api;

import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Flux;

/**
 * Cross-plugin entry point for contributing tools to Halo's MCP server.
 *
 * <p>Providers declare lowercase snake_case names local to their plugin, such as {@code
 * lookup_order}. The MCP server resolves plugin ownership and generates the protocol name. The API
 * intentionally contains no MCP SDK types so provider plugins remain independent of the server's
 * transport and SDK implementation.
 */
public interface McpToolProvider extends ExtensionPoint {

    /**
     * Returns the tools contributed by this provider.
     *
     * <p>The method is evaluated when MCP tools are listed or called, so tools from enabled plugins
     * are visible without rebuilding the MCP server.
     */
    Flux<McpToolDefinition> tools();
}
