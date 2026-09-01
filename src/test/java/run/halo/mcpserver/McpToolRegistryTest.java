package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
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
    PluginManager pluginManager;

    @Mock
    PluginWrapper selfPluginWrapper;

    @Mock
    McpToolProvider provider;

    McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        lenient().when(selfPluginWrapper.getPluginManager()).thenReturn(pluginManager);
        registry = new McpToolRegistry(
                extensionGetter, new McpAuthorization(), pluginManager, Duration.ofSeconds(5));
    }

    @Test
    void springCanCreateTheRegistryWithItsRuntimeDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExtensionGetter.class, () -> extensionGetter);
            context.registerBean(McpAuthorization.class, McpAuthorization::new);
            context.registerBean(PluginWrapper.class, () -> selfPluginWrapper);
            context.register(McpToolRegistry.class);

            context.refresh();

            assertThat(context.getBean(McpToolRegistry.class)).isNotNull();
        }
    }

    @Test
    void executesAValidatedContributedTool() {
        var tool = McpToolDefinition.builder()
                .name("hello")
                .title("Hello")
                .description("Returns a greeting")
                .inputSchema(objectSchema(
                        Map.of("name", Map.of("type", "string")), List.of("name")))
                .outputSchema(objectSchema(
                        Map.of("message", Map.of("type", "string")), List.of("message")))
                .annotations(McpToolAnnotations.readOnly("Hello"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> {
                    assertThat(invocation.toolName()).isEqualTo("hello");
                    return Mono.just(McpToolResult.success(
                            Map.of("message", "Hello " + invocation.arguments().get("name")),
                            "Custom greeting"));
                })
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__hello", Map.of("name", "Halo"))
                        .contextWrite(context("demo__hello")))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().structuredContent().toString()).contains("Hello Halo");
                    assertThat(result.orElseThrow().content().getFirst())
                            .isInstanceOfSatisfying(
                                    io.modelcontextprotocol.spec.McpSchema.TextContent.class,
                                    content -> assertThat(content.text()).isEqualTo("Custom greeting"));
                })
                .verifyComplete();
    }

    @Test
    void validatesArgumentsBeforeExecutingTheProvider() {
        var called = new AtomicBoolean();
        var tool = McpToolDefinition.builder()
                .name("hello")
                .inputSchema(objectSchema(
                        Map.of("name", Map.of("type", "string")), List.of("name")))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> {
                    called.set(true);
                    return Mono.just(McpToolResult.success(Map.of("ok", true)));
                })
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__hello", Map.of("name", 42))
                        .contextWrite(context("demo__hello")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INVALID_ARGUMENT"))
                .verifyComplete();
        assertThat(called).isFalse();
    }

    @Test
    void rejectsStructuredOutputThatDoesNotMatchTheDeclaredSchema() {
        var tool = McpToolDefinition.builder()
                .name("count")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .outputSchema(objectSchema(
                        Map.of("count", Map.of("type", "integer")), List.of("count")))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("count", "one"))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__count", Map.of())
                        .contextWrite(context("demo__count")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INVALID_TOOL_RESULT"))
                .verifyComplete();
    }

    @Test
    void turnsEmptyProviderCallbacksIntoStableErrors() {
        var emptyPermission = McpToolDefinition.builder()
                .name("permission")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.empty())
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        var emptyHandler = McpToolDefinition.builder()
                .name("handler")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.empty())
                .build();
        providerTools(provider, "demo", emptyPermission, emptyHandler);

        StepVerifier.create(registry.executeIfContributed("demo__permission", Map.of())
                        .contextWrite(context("demo__permission")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INTERNAL"))
                .verifyComplete();
        StepVerifier.create(registry.executeIfContributed("demo__handler", Map.of())
                        .contextWrite(context("demo__handler")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INTERNAL"))
                .verifyComplete();
    }

    @Test
    void turnsProviderLinkageErrorsIntoStableErrors() {
        var tool = McpToolDefinition.builder()
                .name("broken")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> {
                    throw new NoSuchMethodError("outdated provider API");
                })
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__broken", Map.of())
                        .contextWrite(context("demo__broken")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("INTERNAL"))
                .expectComplete()
                .verify(Duration.ofSeconds(1));
    }

    @Test
    void returnsEmptyForABuiltInTool() {
        providerTools(provider, "demo", tool("hello"));

        StepVerifier.create(registry.executeIfContributed("halo_search_content", Map.of())
                        .contextWrite(context("halo_search_content")))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void rejectsToolWhenKeyOrProviderPermissionDenies() {
        var tool = McpToolDefinition.builder()
                .name("secret")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> Mono.just(false))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__secret", Map.of())
                        .contextWrite(context("demo__secret")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("FORBIDDEN"))
                .verifyComplete();
        StepVerifier.create(registry.executeIfContributed("demo__secret", Map.of())
                        .contextWrite(context("demo__other")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("FORBIDDEN"))
                .verifyComplete();
    }

    @Test
    void defersTheProviderPermissionCallbackUntilTheKeyAllowlistPasses() {
        var permissionChecks = new AtomicInteger();
        var tool = McpToolDefinition.builder()
                .name("secret")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> {
                    permissionChecks.incrementAndGet();
                    return Mono.just(true);
                })
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        providerTools(provider, "demo", tool);

        StepVerifier.create(registry.executeIfContributed("demo__secret", Map.of())
                        .contextWrite(context("demo__other")))
                .assertNext(result -> assertThat(result.orElseThrow().structuredContent().toString())
                        .contains("FORBIDDEN"))
                .verifyComplete();
        assertThat(permissionChecks).hasValue(0);

        StepVerifier.create(registry.executeIfContributed("demo__secret", Map.of())
                        .contextWrite(context("demo__secret")))
                .assertNext(result -> assertThat(result.orElseThrow().isError()).isFalse())
                .verifyComplete();
        assertThat(permissionChecks).hasValue(1);
    }

    @Test
    void isolatesSynchronousProviderCallbacksFromNonBlockingThreads() {
        var toolsCallbackWasNonBlocking = new AtomicBoolean(true);
        var permissionCallbackWasNonBlocking = new AtomicBoolean(true);
        var handlerCallbackWasNonBlocking = new AtomicBoolean(true);
        var tool = McpToolDefinition.builder()
                .name("thread")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .permission(invocation -> {
                    permissionCallbackWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                    return Mono.just(true);
                })
                .handler(invocation -> {
                    handlerCallbackWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                    return Mono.just(McpToolResult.success(Map.of("ok", true)));
                })
                .build();
        owner(provider, "demo");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenAnswer(ignored -> {
            toolsCallbackWasNonBlocking.set(Schedulers.isInNonBlockingThread());
            return Flux.just(tool);
        });

        StepVerifier.create(registry.executeIfContributed("demo__thread", Map.of())
                        .contextWrite(context("demo__thread"))
                        .subscribeOn(Schedulers.parallel()))
                .assertNext(result -> assertThat(result.orElseThrow().isError()).isFalse())
                .verifyComplete();

        assertThat(toolsCallbackWasNonBlocking).isFalse();
        assertThat(permissionCallbackWasNonBlocking).isFalse();
        assertThat(handlerCallbackWasNonBlocking).isFalse();
    }

    @Test
    void resolvesProviderListForEachRequest() {
        var current = new java.util.concurrent.atomic.AtomicReference<McpToolDefinition>(tool("one"));
        owner(provider, "demo");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenAnswer(invocation -> Flux.just(current.get()));

        assertThat(registry.registeredTools().block())
                .extracting(RegisteredTool::protocolName).containsExactly("demo__one");
        current.set(tool("two"));
        assertThat(registry.registeredTools().block())
                .extracting(RegisteredTool::protocolName).containsExactly("demo__two");
    }

    @Test
    void derivesTheProtocolNameFromTheRuntimeOwner() {
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.just(tool("impersonated")));
        owner(provider, "demo");

        StepVerifier.create(registry.registeredTools())
                .assertNext(tools -> assertThat(tools).singleElement().satisfies(tool -> {
                    assertThat(tool.pluginName()).isEqualTo("demo");
                    assertThat(tool.protocolName()).isEqualTo("demo__impersonated");
                }))
                .verifyComplete();
    }

    @Test
    void quarantinesAProviderWhenPf4jCannotResolveItsOwner() {
        McpToolProvider unknownProvider = () -> Flux.just(tool("hello"));
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(unknownProvider));

        assertThat(registry.registeredTools().block()).isEmpty();
    }

    @Test
    void quarantinesInvalidSchemasAndConflictingTools() {
        var invalidSchema = McpToolDefinition.builder()
                .name("invalid")
                .inputSchema(Map.of("type", "object", "properties", "not-an-object"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of())))
                .build();
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.just(invalidSchema));
        assertThat(registry.registeredTools().block()).isEmpty();

        providerTools(provider, "demo", tool("conflict"), tool("conflict"));
        assertThat(registry.registeredTools().block()).isEmpty();
    }

    @Test
    void isolatesAFailedProviderFromHealthyProviders() {
        McpToolProvider failed = () -> Flux.error(new IllegalStateException("provider secret"));
        McpToolProvider healthy = () -> Flux.just(tool("available"));
        owner(healthy, "healthy");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(failed, healthy));

        assertThat(registry.registeredTools().block())
                .extracting(RegisteredTool::protocolName)
                .containsExactly("healthy__available");
    }

    @Test
    void isolatesANonTerminatingProviderFromHealthyProviders() {
        registry = new McpToolRegistry(
                extensionGetter, new McpAuthorization(), pluginManager, Duration.ofMillis(10));
        McpToolProvider stalled = Flux::never;
        McpToolProvider healthy = () -> Flux.just(tool("available"));
        owner(healthy, "healthy");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(stalled, healthy));

        StepVerifier.create(registry.registeredTools())
                .assertNext(tools -> assertThat(tools)
                        .extracting(RegisteredTool::protocolName)
                        .containsExactly("healthy__available"))
                .verifyComplete();
    }

    @Test
    void isolatesAProviderThatReturnsTooManyTools() {
        McpToolProvider excessive = () -> Flux.range(0, 101)
                .map(index -> tool("tool_" + index));
        McpToolProvider healthy = () -> Flux.just(tool("available"));
        owner(healthy, "healthy");
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(excessive, healthy));

        StepVerifier.create(registry.registeredTools())
                .assertNext(tools -> assertThat(tools)
                        .extracting(RegisteredTool::protocolName)
                        .containsExactly("healthy__available"))
                .verifyComplete();
    }

    private void providerTools(
            McpToolProvider toolProvider, String pluginName, McpToolDefinition... tools) {
        owner(toolProvider, pluginName);
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class))
                .thenReturn(Flux.just(toolProvider));
        when(toolProvider.tools()).thenReturn(Flux.fromArray(tools));
    }

    private void owner(McpToolProvider toolProvider, String pluginName) {
        var plugin = mock(PluginWrapper.class);
        when(plugin.getPluginId()).thenReturn(pluginName);
        when(pluginManager.whichPlugin(AopUtils.getTargetClass(toolProvider))).thenReturn(plugin);
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
