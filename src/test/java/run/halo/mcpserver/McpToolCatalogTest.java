package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.tools.BuiltInTool;
import run.halo.mcpserver.tools.BuiltInTools;
import run.halo.mcpserver.api.McpToolDefinition;

@ExtendWith(MockitoExtension.class)
class McpToolCatalogTest {

    @Mock
    BuiltInTools builtInTools;

    @Mock
    McpToolRegistry registry;

    @Mock
    ReactiveExtensionClient extensionClient;

    @Test
    void separatesProtocolDescriptionsFromChineseConsoleDescriptions() {
        var protocolTool = McpSchema.Tool.builder(
                        "halo_list_posts", Map.of("type", "object", "properties", Map.of()))
                .title("List Halo posts")
                .description("List posts with pagination.")
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        var specification = McpStatelessServerFeatures.AsyncToolSpecification.builder()
                .tool(protocolTool)
                .callHandler((context, request) -> Mono.empty())
                .build();
        var builtIn = new BuiltInTool(specification, "POST", "查询文章", "分页查询文章。");
        when(builtInTools.tools()).thenReturn(List.of(builtIn));
        when(registry.registeredTools()).thenReturn(Mono.just(List.of()));
        var catalog = new McpToolCatalog(builtInTools, registry, extensionClient);

        assertThat(catalog.tools().block()).singleElement().satisfies(tool -> {
            assertThat(tool.title()).isEqualTo("查询文章");
            assertThat(tool.description()).isEqualTo("分页查询文章。");
        });
        assertThat(catalog.protocolTools().block()).singleElement().satisfies(tool -> {
            assertThat(tool.title()).isEqualTo("List Halo posts");
            assertThat(tool.description()).isEqualTo("List posts with pagination.");
        });
    }

    @Test
    void usesProviderDisplayCopyOnlyForTheConsoleCatalog() {
        var definition = McpToolDefinition.builder()
                .name("demo/export")
                .title("Export data")
                .description("Export plugin data.")
                .displayTitle("导出数据")
                .displayDescription("导出插件数据。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.empty())
                .build();
        when(builtInTools.tools()).thenReturn(List.of());
        when(registry.registeredTools())
                .thenReturn(Mono.just(List.of(new RegisteredTool("demo", definition))));
        when(extensionClient.fetch(run.halo.app.core.extension.Plugin.class, "demo"))
                .thenReturn(Mono.error(new IllegalStateException("plugin metadata unavailable")));
        var catalog = new McpToolCatalog(builtInTools, registry, extensionClient);

        assertThat(catalog.tools().block()).singleElement().satisfies(tool -> {
            assertThat(tool.title()).isEqualTo("导出数据");
            assertThat(tool.description()).isEqualTo("导出插件数据。");
        });
        assertThat(catalog.protocolTools().block()).singleElement().satisfies(tool -> {
            assertThat(tool.title()).isEqualTo("Export data");
            assertThat(tool.description()).isEqualTo("Export plugin data.");
        });
    }

    @Test
    void keepsBuiltInToolsWhenContributedToolDiscoveryFails() {
        var protocolTool = McpSchema.Tool.builder(
                        "halo_list_posts", Map.of("type", "object", "properties", Map.of()))
                .title("List Halo posts")
                .description("List posts with pagination.")
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        var specification = McpStatelessServerFeatures.AsyncToolSpecification.builder()
                .tool(protocolTool)
                .callHandler((context, request) -> Mono.empty())
                .build();
        var builtIn = new BuiltInTool(specification, "POST", "查询文章", "分页查询文章。");
        when(builtInTools.tools()).thenReturn(List.of(builtIn));
        when(registry.registeredTools())
                .thenReturn(Mono.error(new IllegalStateException("provider discovery secret")));
        var catalog = new McpToolCatalog(builtInTools, registry, extensionClient);

        assertThat(catalog.tools().block()).singleElement().satisfies(tool ->
                assertThat(tool.name()).isEqualTo("halo_list_posts"));
        assertThat(catalog.protocolTools().block()).singleElement().satisfies(tool ->
                assertThat(tool.name()).isEqualTo("halo_list_posts"));
    }
}
