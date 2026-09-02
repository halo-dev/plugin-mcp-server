package run.halo.mcpserver;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.mcpserver.tools.BuiltInTools;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
class HaloMcpServer {

    private final WebFluxStatelessServerTransport transport;
    private final McpStatelessAsyncServer server;

    HaloMcpServer(
            BuiltInTools builtInTools,
            McpToolRegistry toolRegistry,
            McpToolCatalog toolCatalog,
            McpAuthorization authorization,
            McpRequestRateLimiter rateLimiter,
            McpRecentCallHistory recentCallHistory,
            PluginContext pluginContext) {
        var jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        var mcpJsonMapper = new JacksonMcpJsonMapper(jsonMapper);
        this.transport = WebFluxStatelessServerTransport.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint("/mcp")
                .securityValidator(DefaultServerTransportSecurityValidator.builder().build())
                .build();
        var authorizedTransport = new AuthorizedMcpTransport(
                transport,
                mcpJsonMapper,
                toolCatalog,
                toolRegistry,
                authorization,
                rateLimiter,
                recentCallHistory);
        this.server = McpServer.async(authorizedTransport)
                .jsonMapper(mcpJsonMapper)
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(jsonMapper))
                .serverInfo("halo-mcp-server", pluginContext.getVersion())
                .instructions("Manage posts, single pages, categories, tags, comments, replies, and attachments on this Halo site.")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(builtInTools.specifications())
                .build();
    }

    @SuppressWarnings("unchecked")
    RouterFunction<ServerResponse> routerFunction() {
        return (RouterFunction<ServerResponse>) transport.getRouterFunction();
    }

    Mono<Void> closeGracefully() {
        return server.closeGracefully();
    }

    java.util.List<String> protocolVersions() {
        return transport.protocolVersions();
    }
}
