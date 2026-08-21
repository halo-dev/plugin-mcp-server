package run.halo.mcpserver;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;
import run.halo.mcpserver.tools.BuiltInTool;
import run.halo.mcpserver.tools.BuiltInTools;

@ExtendWith(MockitoExtension.class)
class HaloMcpServerTest {

    @Mock
    ReactiveExtensionClient extensionClient;

    @Mock
    PluginContext pluginContext;

    @Mock
    ExtensionGetter extensionGetter;

    @Mock
    McpToolProvider provider;

    @Mock
    BuiltInTools builtInTools;

    HaloMcpServer server;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        when(pluginContext.getVersion()).thenReturn("1.0.0");
        lenient().when(extensionGetter.getEnabledExtensions(run.halo.mcpserver.api.McpToolProvider.class))
                .thenReturn(Flux.empty());
        var authorization = new McpAuthorization();
        var searchTool = builtInTool("halo_search_content", "Search content");
        var getPostTool = builtInTool("halo_get_post", "Read post");
        lenient().when(builtInTools.tools()).thenReturn(java.util.List.of(searchTool, getPostTool));
        when(builtInTools.specifications()).thenReturn(java.util.List.of(
                searchTool.specification(), getPostTool.specification()));
        var registry = new McpToolRegistry(extensionGetter, authorization);
        var catalog = new McpToolCatalog(builtInTools, registry, extensionClient);
        server = new HaloMcpServer(builtInTools, registry, catalog, authorization, pluginContext);
        var authentication = new McpKeyAuthenticationToken(
                "key-id",
                "admin",
                java.util.Set.of(
                        "halo_search_content",
                        "halo_get_post",
                        "demo/hello"));
        client = WebTestClient.bindToRouterFunction(server.routerFunction())
                .webFilter((exchange, chain) -> chain.filter(exchange)
                        .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                                .withAuthentication(authentication)))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.closeGracefully().block(Duration.ofSeconds(1));
    }

    @Test
    void initializesWithTheSupportedProtocolVersion() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "method": "initialize",
                          "params": {
                            "protocolVersion": "2025-11-25",
                            "capabilities": {},
                            "clientInfo": {"name": "test-client", "version": "1.0.0"}
                          }
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.result.protocolVersion")
                .isEqualTo("2025-11-25")
                .jsonPath("$.result.serverInfo.name")
                .isEqualTo("halo-mcp-server");
    }

    @Test
    void rejectsBrowserOriginsByDefault() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void listsOnlyToolsAllowedByTheCurrentKey() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "method": "tools/list",
                          "params": {}
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()")
                .isEqualTo(2)
                .jsonPath("$.result.tools[0].name")
                .isEqualTo("halo_search_content")
                .jsonPath("$.result.tools[1].name")
                .isEqualTo("halo_get_post");
    }

    @Test
    void listsAndCallsAContributedToolDirectly() {
        var definition = McpToolDefinition.builder()
                .name("demo/hello")
                .title("Hello")
                .description("Returns a greeting")
                .inputSchema(java.util.Map.of(
                        "type", "object",
                        "properties", java.util.Map.of("name", java.util.Map.of("type", "string"))))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(
                        java.util.Map.of("message", "Hello " + invocation.arguments().get("name")))))
                .build();
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.just(definition));

        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()").isEqualTo(3)
                .jsonPath("$.result.tools[2].name").isEqualTo("demo/hello");

        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc":"2.0",
                          "id":4,
                          "method":"tools/call",
                          "params":{"name":"demo/hello","arguments":{"name":"Halo"}}
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.structuredContent.message").isEqualTo("Hello Halo");
    }

    @Test
    void getIsNotSupportedAndOtherPathsAreNotExposed() {
        client.get().uri("/mcp").exchange().expectStatus().isEqualTo(405);
        client.post().uri("/other").exchange().expectStatus().isNotFound();
    }

    private static BuiltInTool builtInTool(String name, String title) {
        var tool = io.modelcontextprotocol.spec.McpSchema.Tool.builder(
                        name,
                        java.util.Map.of("type", "object", "properties", java.util.Map.of()))
                .title(title)
                .description(title)
                .annotations(io.modelcontextprotocol.spec.McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        var specification = io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> Mono.just(
                        io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                                .structuredContent(java.util.Map.of())
                                .build()))
                .build();
        return new BuiltInTool(specification, "TEST", title, title);
    }

}
