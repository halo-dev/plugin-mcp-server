package run.halo.mcpserver.api;

import reactor.core.publisher.Mono;

/** Performs a per-tool authorization check for the current MCP caller. */
@FunctionalInterface
public interface McpToolPermission {

    Mono<Boolean> check(McpToolInvocation invocation);
}
