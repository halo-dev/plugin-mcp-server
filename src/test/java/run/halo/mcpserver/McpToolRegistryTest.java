package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    McpToolProvider provider;

    McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpToolRegistry(extensionGetter, new McpAuthorization());
    }

    @Test
    void executesAContributedToolDirectly() {
        var tool = McpToolDefinition.builder()
                .name("demo/hello")
                .title("Hello")
                .description("Returns a greeting")
                .inputSchema(objectSchema(Map.of("name", Map.of("type", "string"))))
                .annotations(McpToolAnnotations.readOnly("Hello"))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(
                        Map.of("message", "Hello " + invocation.arguments().get("name")))))
                .build();
        providerTools(tool);

        StepVerifier.create(registry.executeIfContributed("demo/hello", Map.of("name", "Halo"))
                        .contextWrite(context("demo/hello")))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().structuredContent().toString()).contains("Hello Halo");
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyForABuiltInTool() {
        providerTools(tool("demo/hello"));

        StepVerifier.create(registry.executeIfContributed("halo_search_content", Map.of())
                        .contextWrite(context("halo_search_content")))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void rejectsToolWhenKeyOrProviderPermissionDenies() {
        var tool = McpToolDefinition.builder()
                .name("demo/secret")
                .inputSchema(objectSchema(Map.of()))
                .permission(invocation -> Mono.just(false))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
        providerTools(tool);

        StepVerifier.create(registry.executeIfContributed("demo/secret", Map.of())
                        .contextWrite(context("demo/secret")))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().structuredContent().toString()).contains("FORBIDDEN");
                })
                .verifyComplete();

        StepVerifier.create(registry.executeIfContributed("demo/secret", Map.of())
                        .contextWrite(context("demo/other")))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().structuredContent().toString()).contains("FORBIDDEN");
                })
                .verifyComplete();
    }

    @Test
    void resolvesProviderListForEachRequest() {
        var current = new java.util.concurrent.atomic.AtomicReference<McpToolDefinition>(tool("demo/one"));
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenAnswer(invocation -> Flux.just(current.get()));

        assertThat(registry.definitions().block()).extracting(McpToolDefinition::name).containsExactly("demo/one");
        current.set(tool("demo/two"));
        assertThat(registry.definitions().block()).extracting(McpToolDefinition::name).containsExactly("demo/two");
    }

    @Test
    void rejectsDuplicateAndNonNamespacedTools() {
        providerTools(tool("demo/conflict"), tool("demo/conflict"));
        StepVerifier.create(registry.definitions())
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("Multiple providers"))
                .verify();

        providerTools(tool("not-namespaced"));
        StepVerifier.create(registry.definitions())
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("plugin-name/tool-name"))
                .verify();
    }

    private void providerTools(McpToolDefinition... tools) {
        when(extensionGetter.getEnabledExtensions(McpToolProvider.class)).thenReturn(Flux.just(provider));
        when(provider.tools()).thenReturn(Flux.fromArray(tools));
    }

    private static McpToolDefinition tool(String name) {
        return McpToolDefinition.builder()
                .name(name)
                .inputSchema(objectSchema(Map.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of("ok", true))))
                .build();
    }

    private static reactor.util.context.Context context(String... tools) {
        return org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(
                new McpKeyAuthenticationToken("key-id", "admin", java.util.Set.of(tools)));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of(),
                "additionalProperties", false);
    }
}
