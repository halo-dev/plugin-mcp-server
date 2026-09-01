package run.halo.mcpserver;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import reactor.core.publisher.Mono;

/** Adds request-scoped tool discovery to the SDK's otherwise global stateless tool registry. */
final class AuthorizedMcpTransport implements McpStatelessServerTransport {

    private static final Logger log = LoggerFactory.getLogger(AuthorizedMcpTransport.class);

    private final WebFluxStatelessServerTransport delegate;
    private final McpJsonMapper jsonMapper;
    private final McpToolCatalog catalog;
    private final McpToolRegistry registry;
    private final McpAuthorization authorization;
    private final McpRequestRateLimiter rateLimiter;
    private final McpRecentCallHistory recentCallHistory;

    AuthorizedMcpTransport(
            WebFluxStatelessServerTransport delegate,
            McpJsonMapper jsonMapper,
            McpToolCatalog catalog,
            McpToolRegistry registry,
            McpAuthorization authorization,
            McpRequestRateLimiter rateLimiter,
            McpRecentCallHistory recentCallHistory) {
        this.delegate = delegate;
        this.jsonMapper = jsonMapper;
        this.catalog = catalog;
        this.registry = registry;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.recentCallHistory = recentCallHistory;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(
                    McpTransportContext context, McpSchema.JSONRPCRequest request) {
                return switch (request.method()) {
                    case "tools/list" -> listTools(request);
                    case "tools/call" -> callTool(context, request, handler);
                    default -> delegatedRequest(context, request, handler);
                };
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
                    // Stateless servers have no session state to update after initialization.
                    return Mono.empty();
                }
                return handler.handleNotification(context, notification);
            }
        });
    }

    private Mono<McpSchema.JSONRPCResponse> listTools(McpSchema.JSONRPCRequest request) {
        return authorization.allowedTools()
                .zipWith(catalog.protocolTools())
                .map(tuple -> tuple.getT2().stream()
                        .filter(tool -> tuple.getT1().contains(tool.name()))
                        .toList())
                .map(tools -> McpSchema.JSONRPCResponse.result(
                        request.id(), McpSchema.ListToolsResult.builder(tools).build()))
                .onErrorResume(error -> Mono.just(internalError(request, error)));
    }

    private Mono<McpSchema.JSONRPCResponse> callTool(
            McpTransportContext context,
            McpSchema.JSONRPCRequest request,
            McpStatelessServerHandler handler) {
        return authorization.authentication().flatMap(authentication -> {
            final McpSchema.CallToolRequest call;
            try {
                call = jsonMapper.convertValue(request.params(), McpSchema.CallToolRequest.class);
            } catch (RuntimeException error) {
                return recentCallHistory.observe(
                        authentication,
                        "",
                        () -> Mono.just(protocolError(request, -32602, "Invalid tools/call parameters")));
            }
            var toolName = call.name() == null ? "" : call.name();
            return recentCallHistory.observe(
                    authentication,
                    toolName,
                    () -> rateLimiter.allowTool(
                                    authentication.keyId(),
                                    authentication.allows(toolName) ? toolName : "<unauthorized>")
                            ? executeTool(context, request, handler, call, toolName)
                            : Mono.just(rateLimited(request)));
        });
    }

    private Mono<McpSchema.JSONRPCResponse> executeTool(
            McpTransportContext context,
            McpSchema.JSONRPCRequest request,
            McpStatelessServerHandler handler,
            McpSchema.CallToolRequest call,
            String toolName) {
        if (McpToolNames.pluginName(toolName).isEmpty()) {
            return delegatedRequest(context, request, handler);
        }
        return registry.executeIfContributed(toolName, arguments(call.arguments()))
                .flatMap(result -> result
                        .map(value -> Mono.just(McpSchema.JSONRPCResponse.result(request.id(), value)))
                        .orElseGet(() -> delegatedRequest(context, request, handler)))
                .onErrorResume(error -> Mono.just(internalError(request, error)));
    }

    private Mono<McpSchema.JSONRPCResponse> delegatedRequest(
            McpTransportContext context,
            McpSchema.JSONRPCRequest request,
            McpStatelessServerHandler handler) {
        final Mono<McpSchema.JSONRPCResponse> response;
        try {
            response = handler.handleRequest(context, request);
        } catch (Throwable error) {
            return Mono.just(internalError(request, error));
        }
        if (response == null) {
            return Mono.just(internalError(
                    request, new IllegalStateException("MCP handler returned no response")));
        }
        return response
                .map(value -> sanitizeInternalError(request, value))
                .switchIfEmpty(Mono.fromSupplier(() -> internalError(
                        request, new IllegalStateException("MCP handler returned no response"))))
                .onErrorResume(McpError.class, error -> error.getJsonRpcError() == null
                        ? Mono.just(internalError(request, error))
                        : Mono.just(sanitizeInternalError(
                                request,
                                McpSchema.JSONRPCResponse.error(
                                        request.id(), error.getJsonRpcError()))))
                .onErrorResume(error -> Mono.just(internalError(request, error)));
    }

    private McpSchema.JSONRPCResponse sanitizeInternalError(
            McpSchema.JSONRPCRequest request, McpSchema.JSONRPCResponse response) {
        if (response.error() != null && Integer.valueOf(-32603).equals(response.error().code())) {
            log.warn("MCP handler returned an internal error for method {}", request.method());
            return protocolError(request, -32603, "Internal error");
        }
        return response;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private static Map<String, Object> arguments(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    private McpSchema.JSONRPCResponse internalError(
            McpSchema.JSONRPCRequest request, Throwable error) {
        log.warn("MCP request failed internally", error);
        return protocolError(request, -32603, "Internal error");
    }

    private static McpSchema.JSONRPCResponse rateLimited(McpSchema.JSONRPCRequest request) {
        var error = run.halo.mcpserver.api.McpToolResult.error(
                "RATE_LIMITED", "Tool invocation rate limit exceeded");
        return McpSchema.JSONRPCResponse.result(
                request.id(),
                McpSchema.CallToolResult.builder()
                        .structuredContent(error.structuredContent())
                        .addTextContent(error.textContent())
                        .isError(true)
                        .build());
    }

    private static McpSchema.JSONRPCResponse protocolError(
            McpSchema.JSONRPCRequest request, int code, String message) {
        return McpSchema.JSONRPCResponse.error(
                request.id(), new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
    }
}
