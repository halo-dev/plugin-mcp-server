package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

@ExtendWith(MockitoExtension.class)
class McpToolRegistryTest {

    @Mock
    ExtensionGetter extensionGetter;

    @Mock
    ReactiveExtensionClient extensionClient;

    @Mock
    McpToolProvider provider;

    McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpToolRegistry(extensionGetter, extensionClient, new McpAuthorization());
    }

    @Test
    void executesAValidatedContributedTool() {
        var tool = McpToolDefinition.builder()
                .name("demo/hello")
                .title("Hello")
                .description("Returns a greeting")
                .inputSchema(objectSchema(
                        Map.of("name", Map.of("type", "string")), List.of("name")))
                .outputSchema(objectSchema(
                        Map.of("message", Map.of("type", "string")), List.of("message")))
                .annotations(McpToolAnnotations.readOnly("Hello"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(
                        Map.of("message", "Hello " + invocation.arguments().get("name")))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo/hello", Map.of("name", "Halo"))
                        .contextWrite(context("demo/hello")))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().structuredContent().toString()).contains("Hello Halo");
                })
                .verifyComplete();
    }

    @Test
    void validatesArgumentsBeforeExecutingTheProvider() {
        var called = new AtomicBoolean();
        var tool = McpToolDefinition.builder()
                .name("demo/hello")
                .inputSchema(objectSchema(
                        Map.of("name", Map.of("type", "string")), List.of("name")))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> {
                    called.set(true);
                    return Mono.just(McpToolResult.success(Map.of("ok", true)));
                })
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo/hello", Map.of("name", 42))
                        .contextWrite(context("demo/hello")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INVALID_ARGUMENTS"))
                .verifyComplete();
        assertThat(called).isFalse();
    }

    @Test
    void rejectsStructuredOutputThatDoesNotMatchTheDeclaredSchema() {
        var tool = McpToolDefinition.builder()
                .name("demo/count")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .outputSchema(objectSchema(
                        Map.of("count", Map.of("type", "integer")), List.of("count")))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("count", "one"))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo/count", Map.of())
                        .contextWrite(context("demo/count")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INVALID_TOOL_RESULT"))
                .verifyComplete();
    }

    @Test
    void turnsEmptyProviderCallbacksIntoStableErrors() {
        var emptyPermission = McpToolDefinition.builder()
                .name("demo/permission")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.empty())
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        var emptyHandler = McpToolDefinition.builder()
                .name("demo/handler")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.empty())
                .build();
        providerTools(provider, "demo", emptyPermission, emptyHandler);

        StepVerifier.create(registry.executeIfContributed("demo/permission", Map.of())
                        .contextWrite(context("demo/permission")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INTERNAL"))
                .verifyComplete();
        StepVerifier.create(registry.executeIfContributed("demo/handler", Map.of())
                        .contextWrite(context("demo/handler")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INTERNAL"))
                .verifyComplete();
    }

    @Test
    void returnsEmptyForABuiltInTool() {
        providerTools(provider, "demo", tool("demo/hello"));

        StepVerifier.create(registry.executeIfContributed("halo_search_content", Map.of())
                        .contextWrite(context("halo_search_content")))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void rejectsToolWhenKeyOrProviderPermissionDenies() {
        var tool = McpToolDefinition.builder()
                .name("demo/secret")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(false))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo/secret", Map.of())
                        .contextWrite(context("demo/secret")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("FORBIDDEN"))
                .verifyComplete();
        StepVerifier.create(registry.executeIfContributed("demo/secret", Map.of())
                        .contextWrite(context("demo/other")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("FORBIDDEN"))
                .verifyComplete();
    }

    @Test
    void resolvesProviderListForEachRequest() {
        var current = new java.util.concurrent.atomic.AtomicReference<McpToolDefinition>(tool("demo/one"));
        owner(provider, "demo");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenAnswer(invocation -> Flux.just(current.get()));

        assertThat(registry.registeredTools().block())
                .extracting(tool -> tool.definition().name()).containsExactly("demo/one");
        current.set(tool("demo/two"));
        assertThat(registry.registeredTools().block())
                .extracting(tool -> tool.definition().name()).containsExactly("demo/two");
    }

    @Test
    void usesTheOwningPluginAsTheRequiredNamespace() {
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.just(tool("another-plugin/impersonated")));
        var impersonated = new Plugin();
        var metadata = new Metadata();
        metadata.setName("another-plugin");
        impersonated.setMetadata(metadata);
        var status = new Plugin.PluginStatus();
        status.setLoadLocation(java.net.URI.create("file:///different-plugin.jar"));
        impersonated.setStatus(status);
        when(extensionClient.fetch(Plugin.class, "another-plugin"))
                .thenReturn(Mono.just(impersonated));

        StepVerifier.create(registry.registeredTools())
                .assertNext(tools -> assertThat(tools).isEmpty())
                .verifyComplete();
    }

    @Test
    void quarantinesInvalidSchemasAndConflictingTools() {
        var invalidSchema = McpToolDefinition.builder()
                .name("demo/invalid")
                .inputSchema(Map.of("type", "object", "properties", "not-an-object"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of())))
                .build();
        providerTools(provider, "demo", invalidSchema);
        assertThat(registry.registeredTools().block()).isEmpty();

        providerTools(provider, "demo", tool("demo/conflict"), tool("demo/conflict"));
        assertThat(registry.registeredTools().block()).isEmpty();
    }

    @Test
    void isolatesAFailedProviderFromHealthyProviders() {
        McpToolProvider failed = () -> Flux.error(new IllegalStateException("provider secret"));
        McpToolProvider healthy = () -> Flux.just(tool("healthy/available"));
        owner(healthy, "healthy");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(failed, healthy));

        assertThat(registry.registeredTools().block())
                .extracting(tool -> tool.definition().name())
                .containsExactly("healthy/available");
    }

    private void providerTools(
            McpToolProvider toolProvider, String pluginName, McpToolDefinition... tools) {
        owner(toolProvider, pluginName);
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(toolProvider));
        when(toolProvider.tools()).thenReturn(Flux.fromArray(tools));
    }

    private void owner(McpToolProvider toolProvider, String pluginName) {
        var plugin = new Plugin();
        var metadata = new Metadata();
        metadata.setName(pluginName);
        plugin.setMetadata(metadata);
        var status = new Plugin.PluginStatus();
        try {
            status.setLoadLocation(toolProvider.getClass()
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .normalize());
        } catch (java.net.URISyntaxException error) {
            throw new AssertionError(error);
        }
        plugin.setStatus(status);
        when(extensionClient.fetch(Plugin.class, pluginName)).thenReturn(Mono.just(plugin));
    }

    private static McpToolDefinition tool(String name) {
        return McpToolDefinition.builder()
                .name(name)
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
    }

    private static reactor.util.context.Context context(String... tools) {
        return org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(
                new McpKeyAuthenticationToken(
                        "key-id", "Automation", "hmcp_key", "admin", java.util.Set.of(tools)));
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }
}
