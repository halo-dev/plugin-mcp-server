package run.halo.mcpserver;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.aop.support.AopUtils;
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
    PluginManager pluginManager;

    @Mock
    BuiltInTools builtInTools;

    HaloMcpServer server;
    McpRecentCallHistory recentCallHistory;
    WebTestClient client;
    McpRequestRateLimiter rateLimiter;

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
        var registry = new McpToolRegistry(
                extensionGetter, authorization, pluginManager, Duration.ofSeconds(5));
        var catalog = new McpToolCatalog(builtInTools, registry, extensionClient);
        recentCallHistory = new McpRecentCallHistory();
        rateLimiter = new McpRequestRateLimiter();
        server = new HaloMcpServer(
                builtInTools,
                registry,
                catalog,
                authorization,
                rateLimiter,
                recentCallHistory,
                pluginContext);
        var authentication = new McpKeyAuthenticationToken(
                "key-id",
                "Automation",
                "hmcp_key",
                "admin",
                java.util.Set.of(
                        "halo_search_content",
                        "halo_get_post",
                        "demo__hello"));
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
                .isEqualTo("halo-mcp-server")
                .jsonPath("$.result.instructions")
                .value(instructions -> org.assertj.core.api.Assertions.assertThat(instructions.toString())
                        .contains("active-theme settings", "untrusted"))
                .jsonPath("$.result.capabilities.resources")
                .doesNotExist();
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
    void returnsJsonRpcErrorForUnsupportedRequestMethods() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": 3,
                          "method": "unsupported/method",
                          "params": {}
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(3)
                .jsonPath("$.error.code")
                .isEqualTo(-32601)
                .jsonPath("$.error.message")
                .isEqualTo("Missing handler for request type: unsupported/method");
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

        org.assertj.core.api.Assertions.assertThat(recentCallHistory
                        .list(new McpRecentCallQuery(1, 20, null, null, null))
                        .total())
                .isZero();
    }

    @Test
    void recordsABuiltInToolCallOnce() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc":"2.0",
                          "id":5,
                          "method":"tools/call",
                          "params":{"name":"halo_get_post","arguments":{"content":"must-not-be-recorded"}}
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        var page = recentCallHistory.list(new McpRecentCallQuery(1, 20, null, null, null));
        org.assertj.core.api.Assertions.assertThat(page.total()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(page.items().getFirst().toolName())
                .isEqualTo("halo_get_post");
        org.assertj.core.api.Assertions.assertThat(page.items().getFirst().outcome())
                .isEqualTo(McpCallOutcome.SUCCESS);
        org.assertj.core.api.Assertions.assertThat(page.items().getFirst().toString())
                .doesNotContain("must-not-be-recorded");
    }

    @Test
    void preservesUntypedFloatingPointPrecisionAtTheProtocolBoundary() {
        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc":"2.0",
                          "id":6,
                          "method":"tools/call",
                          "params":{
                            "name":"halo_get_post",
                            "arguments":{"expectedVersion":1.0000000000000000000001}
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("\"expectedVersion\":1.0000000000000000000001"));
    }

    @Test
    void listsAndCallsAContributedToolDirectly() {
        var definition = McpToolDefinition.builder()
                .name("hello")
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
        var plugin = mock(PluginWrapper.class);
        when(plugin.getPluginId()).thenReturn("demo");
        when(pluginManager.whichPlugin(AopUtils.getTargetClass(provider))).thenReturn(plugin);

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
                .jsonPath("$.result.tools[2].name").isEqualTo("demo__hello");

        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc":"2.0",
                          "id":4,
                          "method":"tools/call",
                          "params":{"name":"demo__hello","arguments":{"name":"Halo"}}
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.structuredContent.message").isEqualTo("Hello Halo")
                .jsonPath("$.result.content[0].text").isEqualTo("{\"message\":\"Hello Halo\"}");

        var page = recentCallHistory.list(new McpRecentCallQuery(1, 20, null, null, null));
        org.assertj.core.api.Assertions.assertThat(page.total()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(page.items().getFirst().toolName())
                .isEqualTo("demo__hello");
        org.assertj.core.api.Assertions.assertThat(page.items().getFirst().sourceType())
                .isEqualTo(McpToolSourceType.PLUGIN);
    }

    @Test
    void wildcardCallsBuiltInAndContributedTools() {
        var definition = McpToolDefinition.builder()
                .name("hello")
                .title("Hello")
                .description("Returns a greeting")
                .inputSchema(java.util.Map.of("type", "object"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(
                        java.util.Map.of("message", "Hello"))))
                .build();
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.just(definition));
        var plugin = mock(PluginWrapper.class);
        when(plugin.getPluginId()).thenReturn("demo");
        when(pluginManager.whichPlugin(AopUtils.getTargetClass(provider))).thenReturn(plugin);
        var authentication = new McpKeyAuthenticationToken(
                "wildcard-key", "Automation", "hmcp_key", "admin", java.util.Set.of("*"));
        var wildcardClient = WebTestClient.bindToRouterFunction(server.routerFunction())
                .webFilter((exchange, chain) -> chain.filter(exchange)
                        .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                                .withAuthentication(authentication)))
                .build();

        for (var toolName : java.util.List.of("halo_get_post", "demo__hello")) {
            wildcardClient.post()
                    .uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                    .bodyValue("""
                            {
                              "jsonrpc":"2.0",
                              "id":11,
                              "method":"tools/call",
                              "params":{"name":"%s","arguments":{}}
                            }
                            """.formatted(toolName))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").doesNotExist()
                    .jsonPath("$.result").exists();
        }
    }

    @Test
    void getIsNotSupportedAndOtherPathsAreNotExposed() {
        client.get().uri("/mcp").exchange().expectStatus().isEqualTo(405);
        client.post().uri("/other").exchange().expectStatus().isNotFound();
    }

    @Test
    void rateLimitsToolInvocationsPerKeyAndTool() {
        for (var i = 0; i < McpRequestRateLimiter.TOOL_CALLS_PER_MINUTE; i++) {
            org.assertj.core.api.Assertions.assertThat(
                    rateLimiter.allowTool("key-id", "halo_get_post")).isTrue();
        }

        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {
                          "jsonrpc":"2.0",
                          "id":9,
                          "method":"tools/call",
                          "params":{"name":"halo_get_post","arguments":{}}
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.structuredContent.error.code").isEqualTo("RATE_LIMITED");
    }

    @Test
    void keepsBuiltInToolsWhenProviderDiscoveryFails() {
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenThrow(new IllegalStateException("storage password must stay private"));

        client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("""
                        {"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()").isEqualTo(2)
                .jsonPath("$.result.tools[0].name").isEqualTo("halo_search_content")
                .jsonPath("$.result.tools[1].name").isEqualTo("halo_get_post")
                .jsonPath("$").value(body -> org.assertj.core.api.Assertions
                        .assertThat(body.toString()).doesNotContain("storage password"));
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
                                .structuredContent(request.arguments())
                                .build()))
                .build();
        return new BuiltInTool(specification, "TEST", title, title);
    }

}
