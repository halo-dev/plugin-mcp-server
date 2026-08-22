package run.halo.mcpserver.api;

import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Flux;

/**
 * Cross-plugin entry point for contributing tools to Halo's MCP server.
 *
 * <p>Providers must use their owning plugin ID as the tool namespace, such as {@code
 * my-plugin/lookup-order} for a plugin whose ID is {@code my-plugin}. The API intentionally contains
 * no MCP SDK types so provider plugins remain independent of the server's transport and SDK
 * implementation.
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
