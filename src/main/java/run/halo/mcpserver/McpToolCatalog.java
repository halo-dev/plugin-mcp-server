package run.halo.mcpserver;

import io.swagger.v3.oas.annotations.media.Schema;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.tools.BuiltInTool;
import run.halo.mcpserver.tools.BuiltInTools;

@Component
class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);
    private static final String BUILT_IN_PLUGIN = "mcp-server";
    private final BuiltInTools builtInTools;
    private final McpToolRegistry toolRegistry;
    private final ReactiveExtensionClient extensionClient;

    McpToolCatalog(
            BuiltInTools builtInTools,
            McpToolRegistry toolRegistry,
            ReactiveExtensionClient extensionClient) {
        this.builtInTools = builtInTools;
        this.toolRegistry = toolRegistry;
        this.extensionClient = extensionClient;
    }

    Mono<List<ToolDescriptor>> tools() {
        var builtInSource = new ToolSource("BUILT_IN", BUILT_IN_PLUGIN, "MCP Server", null, null);
        var builtIn = builtInTools.tools().stream()
                .map(tool -> descriptor(tool, builtInSource))
                .toList();
        return contributedTools().flatMap(definitions ->
                Flux.fromIterable(definitions)
                        .flatMap(tool -> source(tool.pluginName())
                                .map(source -> descriptor(tool, source)))
                        .collectList()
                        .map(contributed -> {
                            var tools = new java.util.ArrayList<>(builtIn);
                            tools.addAll(contributed);
                            return List.copyOf(tools);
                        }));
    }

    Mono<List<McpSchema.Tool>> protocolTools() {
        return contributedTools().map(definitions -> {
            var tools = new java.util.ArrayList<>(builtInTools.tools().stream()
                    .map(BuiltInTool::protocolTool)
                    .toList());
            definitions.stream()
                    .map(McpToolCatalog::protocolTool)
                    .forEach(tools::add);
            return List.copyOf(tools);
        });
    }

    Mono<Boolean> hasProtocolTool(String name) {
        if (builtInTools.tools().stream().anyMatch(tool -> tool.protocolTool().name().equals(name))) {
            return Mono.just(true);
        }
        return contributedTools().map(tools ->
                tools.stream().anyMatch(tool -> tool.protocolName().equals(name)));
    }

    private Mono<List<RegisteredTool>> contributedTools() {
        return toolRegistry.registeredTools().onErrorResume(error -> {
            log.warn("Ignoring unavailable contributed MCP tools", error);
            return Mono.just(List.of());
        });
    }

    Mono<Set<String>> availableNames() {
        return tools().map(tools -> tools.stream()
                .map(ToolDescriptor::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private Mono<ToolSource> source(String pluginName) {
        var fallback = new ToolSource("PLUGIN", pluginName, pluginName, null, null);
        return extensionClient.fetch(Plugin.class, pluginName)
                .map(plugin -> {
                    var spec = plugin.getSpec();
                    var displayName = spec == null || spec.getDisplayName() == null
                            ? pluginName
                            : spec.getDisplayName();
                    var version = spec == null ? null : spec.getVersion();
                    var logo = plugin.getStatus() == null ? null : plugin.getStatus().getLogo();
                    if (logo == null && spec != null) {
                        logo = spec.getLogo();
                    }
                    return new ToolSource("PLUGIN", pluginName, displayName, version, logo);
                })
                .onErrorResume(error -> {
                    log.warn("Using fallback metadata for contributed MCP tools from {}", pluginName, error);
                    return Mono.just(fallback);
                })
                .defaultIfEmpty(fallback);
    }

    private static ToolDescriptor descriptor(BuiltInTool tool, ToolSource source) {
        return new ToolDescriptor(
                tool.protocolTool().name(),
                tool.displayTitle(),
                tool.displayDescription(),
                tool.protocolTool().inputSchema(),
                tool.protocolTool().outputSchema(),
                tool.category(),
                Boolean.TRUE.equals(tool.protocolTool().annotations().readOnlyHint()),
                Boolean.TRUE.equals(tool.protocolTool().annotations().destructiveHint()),
                true,
                source);
    }

    private static ToolDescriptor descriptor(RegisteredTool tool, ToolSource source) {
        var definition = tool.definition();
        return new ToolDescriptor(
                tool.protocolName(),
                definition.displayTitle(),
                definition.displayDescription(),
                definition.inputSchema(),
                definition.outputSchema(),
                "PLUGIN",
                definition.annotations().readOnlyHint(),
                definition.annotations().destructiveHint(),
                true,
                source);
    }

    private static McpSchema.Tool protocolTool(RegisteredTool tool) {
        var definition = tool.definition();
        var annotations = definition.annotations();
        var builder = McpSchema.Tool.builder(tool.protocolName(), definition.inputSchema())
                .title(definition.title())
                .description(definition.description())
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(annotations.title())
                        .readOnlyHint(annotations.readOnlyHint())
                        .destructiveHint(annotations.destructiveHint())
                        .idempotentHint(annotations.idempotentHint())
                        .openWorldHint(annotations.openWorldHint())
                        .build());
        if (definition.outputSchema() != null) {
            builder.outputSchema(definition.outputSchema());
        }
        return builder.build();
    }

    @Schema(name = "McpToolSource")
    record ToolSource(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            allowableValues = {"BUILT_IN", "PLUGIN"})
                    String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pluginName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            String version,
            String logo) {}

    @Schema(name = "McpTool")
    record ToolDescriptor(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            String title,
            String description,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            type = "object",
                            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
                    Map<String, Object> inputSchema,
            @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
                    Map<String, Object> outputSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean readOnly,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean destructive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ToolSource source) {}
}
