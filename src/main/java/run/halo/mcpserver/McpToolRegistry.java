package run.halo.mcpserver;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;
import tools.jackson.databind.json.JsonMapper;

/** Resolves and validates tools contributed by enabled Halo plugins for every request. */
@Component
class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ExtensionGetter extensionGetter;
    private final ReactiveExtensionClient extensionClient;
    private final McpAuthorization authorization;
    private final JsonSchemaValidator schemaValidator;

    McpToolRegistry(
            ExtensionGetter extensionGetter,
            ReactiveExtensionClient extensionClient,
            McpAuthorization authorization) {
        this.extensionGetter = extensionGetter;
        this.extensionClient = extensionClient;
        this.authorization = authorization;
        this.schemaValidator = new DefaultJsonSchemaValidator(JsonMapper.shared());
    }

    Mono<Optional<McpSchema.CallToolResult>> executeIfContributed(
            String name, Map<String, Object> arguments) {
        return registeredTools()
                .map(tools -> tools.stream()
                        .filter(tool -> tool.definition().name().equals(name))
                        .findFirst())
                .flatMap(tool -> tool
                        .map(value -> gateway(() -> authorization.require(name)
                                        .then(Mono.defer(() -> execute(value.definition(), arguments))))
                                .map(Optional::of))
                        .orElseGet(() -> Mono.just(Optional.empty())));
    }

    private Mono<McpSchema.CallToolResult> execute(
            McpToolDefinition definition, Map<String, Object> arguments) {
        var inputValidation = schemaValidator.validate(definition.inputSchema(), arguments);
        if (!inputValidation.valid()) {
            return Mono.just(result(McpToolResult.error(
                    "INVALID_ARGUMENTS", "Tool arguments do not match the declared input schema")));
        }
        var invocation = new McpToolInvocation(definition.name(), arguments);
        return checked(
                        definition.permission().check(invocation),
                        "Tool permission callback returned no result")
                .flatMap(allowed -> {
                    if (!allowed) {
                        return Mono.error(new McpToolException(
                                "FORBIDDEN", "The caller is not authorized to use " + definition.name()));
                    }
                    return checked(
                            definition.handler().execute(invocation),
                            "Tool handler returned no result");
                })
                .flatMap(toolResult -> validateResult(definition, toolResult))
                .map(this::result)
                .onErrorResume(this::errorResult);
    }

    private Mono<McpToolResult> validateResult(
            McpToolDefinition definition, McpToolResult result) {
        if (!result.error() && definition.outputSchema() != null) {
            var validation = schemaValidator.validate(
                    definition.outputSchema(), result.structuredContent());
            if (!validation.valid()) {
                log.warn("Contributed MCP tool {} returned structured content that does not match its output schema",
                        definition.name());
                return Mono.error(new McpToolException(
                        "INVALID_TOOL_RESULT",
                        "Tool result does not match the declared output schema"));
            }
        }
        return Mono.just(result);
    }

    private Mono<McpSchema.CallToolResult> gateway(
            Supplier<Mono<McpSchema.CallToolResult>> action) {
        return authorization.authentication()
                .flatMap(ignored -> {
                    try {
                        return checked(action.get(), "Tool call returned no result");
                    } catch (Throwable error) {
                        return Mono.error(error);
                    }
                })
                .onErrorResume(this::errorResult);
    }

    Mono<List<RegisteredTool>> registeredTools() {
        Flux<McpToolProvider> providers;
        try {
            providers = extensionGetter.getEnabledExtensions(McpToolProvider.class);
        } catch (Throwable error) {
            return Mono.error(new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "MCP tool providers are unavailable", error));
        }
        if (providers == null) {
            return Mono.error(new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "MCP tool providers are unavailable"));
        }
        return providers
                .flatMap(this::providerTools)
                .collectList()
                .map(this::withoutConflicts);
    }

    private Flux<RegisteredTool> providerTools(McpToolProvider provider) {
        final Flux<McpToolDefinition> definitions;
        try {
            definitions = provider.tools();
            if (definitions == null) {
                throw new McpToolException(
                        "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider returned no tool stream");
            }
        } catch (Throwable error) {
            logProviderFailure(provider, error);
            return Flux.empty();
        }
        return definitions
                .collectList()
                .flatMapMany(tools -> {
                    try {
                        if (tools.isEmpty()) {
                            return Flux.empty();
                        }
                        var pluginName = validateProviderTools(tools);
                        return verifiedProviderOwner(provider, pluginName)
                                .flatMapMany(ignored -> Flux.fromIterable(tools)
                                        .map(definition -> new RegisteredTool(pluginName, definition)));
                    } catch (Throwable error) {
                        logProviderFailure(provider, error);
                        return Flux.empty();
                    }
                })
                .onErrorResume(error -> {
                    logProviderFailure(provider, error);
                    return Flux.empty();
                });
    }

    private String validateProviderTools(List<McpToolDefinition> definitions) {
        String pluginName = null;
        for (var definition : definitions) {
            if (definition == null) {
                throw new McpToolException(
                        "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider returned a null tool");
            }
            var namespace = namespace(definition.name());
            if (namespace == null || (pluginName != null && !pluginName.equals(namespace))) {
                throw new McpToolException(
                        "INVALID_TOOL_NAME",
                        "A provider must use one plugin ID as the namespace for all of its tools");
            }
            pluginName = namespace;
            validateSchema(definition.name(), "input", definition.inputSchema());
            if (definition.outputSchema() != null) {
                validateSchema(definition.name(), "output", definition.outputSchema());
            }
        }
        return pluginName;
    }

    private Mono<String> verifiedProviderOwner(McpToolProvider provider, String pluginName) {
        final URI providerLocation;
        try {
            var providerClass = ClassUtils.getUserClass(provider);
            var codeSource = providerClass.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Mono.error(new McpToolException(
                        "TOOL_PROVIDER_UNAVAILABLE", "Cannot determine the MCP tool provider location"));
            }
            providerLocation = codeSource.getLocation().toURI().normalize();
        } catch (Exception error) {
            return Mono.error(new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "Cannot determine the MCP tool provider location", error));
        }
        return extensionClient.fetch(Plugin.class, pluginName)
                .filter(plugin -> plugin.getStatus() != null
                        && plugin.getStatus().getLoadLocation() != null
                        && ownsProvider(plugin.getStatus().getLoadLocation(), providerLocation))
                .map(ignored -> pluginName)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "INVALID_TOOL_NAME",
                        "Contributed tool namespace does not match its provider plugin")));
    }

    static boolean ownsProvider(URI pluginLocation, URI providerLocation) {
        var normalizedPlugin = pluginLocation.normalize();
        var normalizedProvider = providerLocation.normalize();
        if (normalizedPlugin.equals(normalizedProvider)) {
            return true;
        }
        if (!"file".equalsIgnoreCase(normalizedPlugin.getScheme())
                || !"file".equalsIgnoreCase(normalizedProvider.getScheme())) {
            return false;
        }
        try {
            var pluginPath = Path.of(normalizedPlugin).toRealPath();
            var providerPath = Path.of(normalizedProvider).toRealPath();
            return Files.isDirectory(pluginPath) && providerPath.startsWith(pluginPath);
        } catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    private void validateSchema(String toolName, String kind, Map<String, Object> schema) {
        var validation = schemaValidator.validateSchema(schema);
        if (!validation.valid()) {
            throw new McpToolException(
                    "INVALID_TOOL_SCHEMA", "Tool " + toolName + " has an invalid " + kind + " schema");
        }
    }

    private List<RegisteredTool> withoutConflicts(List<RegisteredTool> tools) {
        var byName = new LinkedHashMap<String, List<RegisteredTool>>();
        tools.forEach(tool -> byName.computeIfAbsent(
                tool.definition().name(), ignored -> new ArrayList<>()).add(tool));
        var conflicts = byName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            log.warn("Ignoring conflicting contributed MCP tools: {}", conflicts);
        }
        return byName.values().stream()
                .filter(matches -> matches.size() == 1)
                .map(List::getFirst)
                .toList();
    }

    private McpSchema.CallToolResult result(McpToolResult result) {
        var builder = McpSchema.CallToolResult.builder()
                .structuredContent(result.structuredContent())
                .isError(result.error());
        var textContent = result.textContent();
        if (textContent == null && result.structuredContent() != null) {
            textContent = JsonMapper.shared().writeValueAsString(result.structuredContent());
        }
        if (textContent != null) {
            builder.addTextContent(textContent);
        }
        return builder.build();
    }

    private Mono<McpSchema.CallToolResult> errorResult(Throwable error) {
        var toolError = error instanceof McpToolException exception
                ? exception
                : error instanceof AccessDeniedException
                        ? new McpToolException("FORBIDDEN", "The caller is not authorized", error)
                        : new McpToolException("INTERNAL", "The contributed tool call failed", error);
        if (!(error instanceof McpToolException) && !(error instanceof AccessDeniedException)) {
            log.warn("Contributed MCP tool call failed", error);
        }
        return Mono.just(result(McpToolResult.error(toolError.code(), toolError.getMessage())));
    }

    private static String namespace(String toolName) {
        var separator = toolName.indexOf('/');
        return separator > 0
                        && separator < toolName.length() - 1
                        && toolName.indexOf('/', separator + 1) < 0
                ? toolName.substring(0, separator)
                : null;
    }

    private static void logProviderFailure(McpToolProvider provider, Throwable error) {
        log.warn("Ignoring unavailable MCP tool provider {}", provider.getClass().getName(), error);
    }

    private static <T> Mono<T> checked(Mono<T> mono, String message) {
        return mono == null
                ? Mono.error(new McpToolException("INTERNAL", message))
                : mono.switchIfEmpty(Mono.error(new McpToolException("INTERNAL", message)));
    }
}

record RegisteredTool(String pluginName, McpToolDefinition definition) {}
