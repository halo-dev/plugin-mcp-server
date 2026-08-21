package run.halo.mcpserver.api;

import reactor.core.publisher.Mono;

/** Executes a contributed MCP tool after permission checking. */
@FunctionalInterface
public interface McpToolHandler {

    Mono<McpToolResult> execute(McpToolInvocation invocation);
}
