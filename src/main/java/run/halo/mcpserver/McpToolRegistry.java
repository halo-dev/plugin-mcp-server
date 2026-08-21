package run.halo.mcpserver;

import io.modelcontextprotocol.spec.McpSchema;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/** Resolves tools contributed by enabled Halo plugins for every list or call request. */
@Component
class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ExtensionGetter extensionGetter;
    private final McpAuthorization authorization;

    McpToolRegistry(ExtensionGetter extensionGetter, McpAuthorization authorization) {
        this.extensionGetter = extensionGetter;
        this.authorization = authorization;
    }

    Mono<Optional<McpSchema.CallToolResult>> executeIfContributed(
            String name, Map<String, Object> arguments) {
        return definitions()
                .map(definitions -> definitions.stream()
                        .filter(definition -> definition.name().equals(name))
                        .findFirst())
                .flatMap(definition -> definition
                        .map(value -> gateway(() -> authorization.require(name)
                                        .then(execute(value, arguments)))
                                .map(Optional::of))
                        .orElseGet(() -> Mono.just(Optional.empty())));
    }

    private Mono<McpSchema.CallToolResult> execute(
            McpToolDefinition definition, Map<String, Object> arguments) {
        var invocation = new McpToolInvocation(definition.name(), arguments);
        return checked(definition.permission().check(invocation))
                .flatMap(allowed -> {
                    if (!allowed) {
                        return Mono.error(new McpToolException(
                                "FORBIDDEN", "The caller is not authorized to use " + definition.name()));
                    }
                    return checked(definition.handler().execute(invocation));
                })
                .map(this::result)
                .onErrorResume(this::errorResult);
    }

    private Mono<McpSchema.CallToolResult> gateway(
            Supplier<Mono<McpSchema.CallToolResult>> action) {
        return authorization.authentication()
                .flatMap(ignored -> {
                    try {
                        return checked(action.get());
                    } catch (Throwable error) {
                        return Mono.error(error);
                    }
                })
                .onErrorResume(this::errorResult);
    }

    Mono<List<McpToolDefinition>> definitions() {
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
                .flatMap(this::validateDefinitions);
    }

    private Flux<McpToolDefinition> providerTools(McpToolProvider provider) {
        try {
            var tools = provider.tools();
            if (tools == null) {
                return Flux.error(new McpToolException(
                        "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider returned no tool stream"));
            }
            return tools.onErrorMap(error -> new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider failed", error));
        } catch (Throwable error) {
            log.warn("MCP tool provider failed while listing tools", error);
            return Flux.error(new McpToolException(
                    "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider failed", error));
        }
    }

    private Mono<List<McpToolDefinition>> validateDefinitions(List<McpToolDefinition> definitions) {
        var byName = new LinkedHashMap<String, List<McpToolDefinition>>();
        for (var definition : definitions) {
            if (definition == null) {
                return Mono.error(new McpToolException(
                        "TOOL_PROVIDER_UNAVAILABLE", "A MCP tool provider returned a null tool"));
            }
            if (!isNamespaced(definition.name())) {
                return Mono.error(new McpToolException(
                        "INVALID_TOOL_NAME",
                        "Contributed tool names must use plugin-name/tool-name: " + definition.name()));
            }
            byName.computeIfAbsent(definition.name(), ignored -> new ArrayList<>()).add(definition);
        }
        var conflicts = byName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            return Mono.error(new McpToolException(
                    "TOOL_CONFLICT", "Multiple providers registered the same tool: " + conflicts));
        }
        return Mono.just(definitions);
    }

    private McpSchema.CallToolResult result(McpToolResult result) {
        var builder = McpSchema.CallToolResult.builder()
                .structuredContent(result.structuredContent())
                .isError(result.error());
        if (result.textContent() != null) {
            builder.addTextContent(result.textContent());
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

    private static boolean isNamespaced(String name) {
        var separator = name.indexOf('/');
        return separator > 0 && separator < name.length() - 1;
    }

    private static <T> Mono<T> checked(Mono<T> mono) {
        return mono == null
                ? Mono.error(new McpToolException("INTERNAL", "Tool callback returned no result"))
                : mono;
    }
}
