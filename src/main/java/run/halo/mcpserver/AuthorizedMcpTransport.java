package run.halo.mcpserver;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import reactor.core.publisher.Mono;

/** Adds request-scoped tool discovery to the SDK's otherwise global stateless tool registry. */
final class AuthorizedMcpTransport implements McpStatelessServerTransport {

    private final WebFluxStatelessServerTransport delegate;
    private final McpJsonMapper jsonMapper;
    private final McpToolCatalog catalog;
    private final McpToolRegistry registry;
    private final McpAuthorization authorization;
    private final McpRecentCallHistory recentCallHistory;

    AuthorizedMcpTransport(
            WebFluxStatelessServerTransport delegate,
            McpJsonMapper jsonMapper,
            McpToolCatalog catalog,
            McpToolRegistry registry,
            McpAuthorization authorization,
            McpRecentCallHistory recentCallHistory) {
        this.delegate = delegate;
        this.jsonMapper = jsonMapper;
        this.catalog = catalog;
        this.registry = registry;
        this.authorization = authorization;
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
                    default -> handler.handleRequest(context, request)
                            .onErrorResume(McpError.class, error -> Mono.just(
                                    McpSchema.JSONRPCResponse.error(
                                            request.id(), error.getJsonRpcError())));
                };
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
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
                    () -> executeTool(context, request, handler, call, toolName));
        });
    }

    private Mono<McpSchema.JSONRPCResponse> executeTool(
            McpTransportContext context,
            McpSchema.JSONRPCRequest request,
            McpStatelessServerHandler handler,
            McpSchema.CallToolRequest call,
            String toolName) {
        if (!toolName.contains("/")) {
            return handler.handleRequest(context, request);
        }
        return registry.executeIfContributed(toolName, arguments(call.arguments()))
                .flatMap(result -> result
                        .map(value -> Mono.just(McpSchema.JSONRPCResponse.result(request.id(), value)))
                        .orElseGet(() -> handler.handleRequest(context, request)))
                .onErrorResume(error -> Mono.just(internalError(request, error)));
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

    private static McpSchema.JSONRPCResponse internalError(
            McpSchema.JSONRPCRequest request, Throwable error) {
        return protocolError(request, -32603, error.getMessage());
    }

    private static McpSchema.JSONRPCResponse protocolError(
            McpSchema.JSONRPCRequest request, int code, String message) {
        return McpSchema.JSONRPCResponse.error(
                request.id(), new McpSchema.JSONRPCResponse.JSONRPCError(code, message));
    }
}
