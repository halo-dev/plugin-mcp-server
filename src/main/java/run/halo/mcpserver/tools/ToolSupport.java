package run.halo.mcpserver.tools;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.Extension;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

abstract class ToolSupport {

    private static final Logger rollbackLog = LoggerFactory.getLogger(ToolSupport.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final McpSchema.ToolAnnotations READ_ONLY = annotations(true, false, true, false);
    static final McpSchema.ToolAnnotations CREATE = annotations(false, false, false, false);
    static final McpSchema.ToolAnnotations UPDATE = annotations(false, true, true, false);
    static final McpSchema.ToolAnnotations DESTRUCTIVE = annotations(false, true, true, false);
    static final McpSchema.ToolAnnotations RESTORE = annotations(false, false, true, false);
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

    private final Logger log = LoggerFactory.getLogger(getClass());
    final McpAuthorization authorization;

    ToolSupport(McpAuthorization authorization) {
        this.authorization = authorization;
    }

    BuiltInTool tool(
            String name,
            String protocolTitle,
            String protocolDescription,
            String displayTitle,
            String displayDescription,
            String category,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            McpSchema.ToolAnnotations annotations,
            Function<Map<String, Object>, Mono<ToolPayload>> handler) {
        var protocolTool = McpSchema.Tool.builder(name, inputSchema)
                .title(protocolTitle)
                .description(protocolDescription)
                .outputSchema(outputSchema)
                .annotations(annotations)
                .build();
        var specification = McpStatelessServerFeatures.AsyncToolSpecification.builder()
                .tool(protocolTool)
                .callHandler((context, request) -> authorization
                        .authorize(name, () -> invoke(handler, arguments(request.arguments())))
                        .onErrorResume(this::errorResult))
                .build();
        return new BuiltInTool(specification, category, displayTitle, displayDescription);
    }

    private Mono<McpSchema.CallToolResult> invoke(
            Function<Map<String, Object>, Mono<ToolPayload>> handler, Map<String, Object> arguments) {
        try {
            return handler.apply(arguments).map(payload -> McpSchema.CallToolResult.builder()
                    .addTextContent(jsonText(payload.data()))
                    .addTextContent(payload.summary())
                    .structuredContent(payload.data())
                    .build());
        } catch (Throwable error) {
            return Mono.error(error);
        }
    }

    private static String jsonText(Object data) {
        try {
            return JSON_MAPPER.writeValueAsString(data);
        } catch (JacksonException error) {
            throw new McpToolException("INTERNAL", "The tool returned invalid structured content", error);
        }
    }

    private Mono<McpSchema.CallToolResult> errorResult(Throwable error) {
        if (!(error instanceof McpToolException) && !(error instanceof AccessDeniedException)) {
            log.warn("Built-in MCP tool call failed", error);
        }
        var toolError = toToolException(error);
        return Mono.just(McpSchema.CallToolResult.builder()
                .addTextContent(toolError.code() + ": " + toolError.getMessage())
                .structuredContent(Map.of(
                        "error", Map.of("code", toolError.code(), "message", toolError.getMessage())))
                .isError(true)
                .build());
    }

    static ToolPayload payload(Object data, String summary) {
        return new ToolPayload(data, summary);
    }

    static Mono<Void> checkVersion(Long actual, Long expected) {
        return expected == null || expected.equals(actual)
                ? Mono.empty()
                : Mono.error(new McpToolException(
                        "CONFLICT", "The resource changed; expected version " + expected + ", actual " + actual));
    }

    static <T> Mono<T> notFound(String type, String name) {
        return Mono.error(new McpToolException("NOT_FOUND", type + " not found: " + name));
    }

    static <T> Mono<T> unavailable(String name) {
        return Mono.error(new McpToolException("CONTENT_UNAVAILABLE", "Content is unavailable for " + name));
    }

    static <E extends Extension> Mono<E> updateLatest(
            ReactiveExtensionClient client,
            Class<E> type,
            String name,
            String resourceType,
            Consumer<E> mutation) {
        return Mono.defer(() -> client.fetch(type, name)
                        .switchIfEmpty(notFound(resourceType, name))
                        .flatMap(resource -> {
                            mutation.accept(resource);
                            return client.update(resource);
                        }))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(25))
                        .maxBackoff(Duration.ofMillis(100))
                        .filter(OptimisticLockingFailureException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    static <E extends Extension> Mono<E> updateLatestIf(
            ReactiveExtensionClient client,
            Class<E> type,
            String name,
            String resourceType,
            Predicate<E> precondition,
            Supplier<McpToolException> conflict,
            Consumer<E> mutation) {
        return Mono.defer(() -> client.fetch(type, name)
                        .switchIfEmpty(notFound(resourceType, name))
                        .flatMap(resource -> {
                            if (!precondition.test(resource)) {
                                return Mono.error(conflict.get());
                            }
                            mutation.accept(resource);
                            return client.update(resource);
                        }))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(25))
                        .maxBackoff(Duration.ofMillis(100))
                        .filter(OptimisticLockingFailureException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    static <E extends Extension> Mono<Void> rollbackCreated(
            ReactiveExtensionClient client, E resource) {
        return client.delete(resource).then().onErrorResume(error -> {
            rollbackLog.warn(
                    "Failed to roll back created {} {}",
                    resource.getClass().getSimpleName(),
                    resource.getMetadata().getName(),
                    error);
            return Mono.empty();
        });
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return map(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    static Map<String, Object> pageOutputSchema(Map<String, Object> itemSchema) {
        return objectSchema(
                map(
                        "items", described(outputArraySchema(itemSchema), "Items in the current page."),
                        "page", described(outputIntegerSchema(), "One-based current page number."),
                        "size", described(outputIntegerSchema(), "Maximum number of items requested per page."),
                        "total", described(outputIntegerSchema(), "Total number of matching items."),
                        "totalPages", described(outputIntegerSchema(), "Total number of matching pages."),
                        "hasNext", described(booleanSchema(), "Whether another page is available.")),
                List.of("items", "page", "size", "total", "totalPages", "hasNext"));
    }

    static Map<String, Object> contentOutputSchema(Map<String, Object> itemSchema) {
        return objectSchema(
                map(
                        "item", described(itemSchema, "Content item metadata and publication state."),
                        "content", described(
                                objectSchema(
                                        map(
                                                "snapshotName",
                                                        described(
                                                                nullableOutputStringSchema(),
                                                                "Content snapshot metadata.name."),
                                                "rawType",
                                                        described(
                                                                nullableOutputStringSchema(),
                                                                "Format identifier of the raw content."),
                                                "raw",
                                                        described(
                                                                nullableOutputStringSchema(),
                                                                "Raw content when requested by format."),
                                                "rendered",
                                                        described(
                                                                nullableOutputStringSchema(),
                                                                "Rendered HTML content when requested by format.")),
                                        List.of("snapshotName", "rawType")),
                                "Content loaded from the selected snapshot."),
                        "truncated",
                                described(
                                        booleanSchema(),
                                        "Whether raw or rendered content was shortened to the response limit.")),
                List.of("item", "content", "truncated"));
    }

    static Map<String, Object> described(Map<String, Object> schema, String description) {
        var result = new LinkedHashMap<>(schema);
        result.put("description", description);
        return result;
    }

    static Map<String, Object> nullableOutputStringSchema() {
        return Map.of("type", List.of("string", "null"));
    }

    static Map<String, Object> outputIntegerSchema() {
        return Map.of("type", "integer");
    }

    static Map<String, Object> outputArraySchema(Map<String, Object> items) {
        return map("type", "array", "items", items);
    }

    static Map<String, Object> stringSchema() {
        return Map.of("type", "string", "minLength", 1);
    }

    static Map<String, Object> stringSchema(String description) {
        return map("type", "string", "minLength", 1, "description", description);
    }

    static Map<String, Object> stringSchemaWithDefault(String defaultValue) {
        return map("type", "string", "minLength", 1, "default", defaultValue);
    }

    static Map<String, Object> nullableStringSchema() {
        return Map.of("type", List.of("string", "null"));
    }

    static Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    static Map<String, Object> booleanSchema(boolean defaultValue) {
        return Map.of("type", "boolean", "default", defaultValue);
    }

    static Map<String, Object> integerSchema() {
        return Map.of("type", "integer", "minimum", 1);
    }

    static Map<String, Object> integerSchema(int minimum, Integer maximum, int defaultValue) {
        var schema = map("type", "integer", "minimum", minimum, "default", defaultValue);
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        return schema;
    }

    static Map<String, Object> nonNegativeIntegerSchema(int defaultValue) {
        return Map.of("type", "integer", "minimum", 0, "default", defaultValue);
    }

    static Map<String, Object> nonNegativeIntegerSchema() {
        return Map.of("type", "integer", "minimum", 0);
    }

    static Map<String, Object> arrayStringSchema() {
        return arraySchema(Map.of("type", "string"));
    }

    static Map<String, Object> arraySchema(Map<String, Object> items) {
        return map("type", "array", "items", items, "uniqueItems", true);
    }

    static <E extends Enum<E>> Map<String, Object> enumSchema(Class<E> type) {
        return enumSchema(type, null);
    }

    static <E extends Enum<E>> Map<String, Object> enumSchema(Class<E> type, String defaultValue) {
        var schema = map(
                "type", "string",
                "enum", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        return schema;
    }

    static String requiredString(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a non-empty string");
        }
        return text;
    }

    static String resourceName(Map<String, Object> arguments, String name) {
        var value = requiredString(arguments, name);
        if (!value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a lowercase DNS label");
        }
        return value;
    }

    static String optionalString(Map<String, Object> arguments, String name, String defaultValue) {
        var value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a non-empty string");
        }
        return text;
    }

    static String nullableString(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a string or null");
        }
        return text;
    }

    static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean defaultValue) {
        var value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a boolean");
        }
        return booleanValue;
    }

    static boolean requiredBoolean(Map<String, Object> arguments, String name) {
        if (!arguments.containsKey(name)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a boolean");
        }
        return optionalBoolean(arguments, name, false);
    }

    static Boolean optionalBoolean(Map<String, Object> arguments, String name) {
        return arguments.containsKey(name) ? optionalBoolean(arguments, name, false) : null;
    }

    static Long optionalLong(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a positive integer");
        }
        return number.longValue();
    }

    static long requiredLong(Map<String, Object> arguments, String name) {
        var value = optionalLong(arguments, name);
        if (value == null) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a positive integer");
        }
        return value;
    }

    static int boundedInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        var value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || number.intValue() < min || number.intValue() > max) {
            throw new McpToolException(
                    "INVALID_ARGUMENT", name + " must be between " + min + " and " + max);
        }
        return number.intValue();
    }

    static int optionalNonNegativeInt(Map<String, Object> arguments, String name, int defaultValue) {
        var value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be a non-negative integer");
        }
        return number.intValue();
    }

    static <E extends Enum<E>> E requiredEnum(Map<String, Object> arguments, String name, Class<E> type) {
        var value = requiredString(arguments, name);
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new McpToolException("INVALID_ARGUMENT", name + " has an unsupported value", error);
        }
    }

    static <E extends Enum<E>> E optionalEnum(
            Map<String, Object> arguments, String name, Class<E> type, E defaultValue) {
        return arguments.containsKey(name) ? requiredEnum(arguments, name, type) : defaultValue;
    }

    static List<String> stringList(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be an array of strings");
        }
        return list.stream().map(item -> {
            if (!(item instanceof String text) || !StringUtils.hasText(text)) {
                throw new McpToolException("INVALID_ARGUMENT", name + " must contain non-empty strings");
            }
            return text;
        }).distinct().toList();
    }

    static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }

    static LinkedHashMap<String, Object> map(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            if (values[i + 1] != null) {
                result.put((String) values[i], values[i + 1]);
            }
        }
        return result;
    }

    private static Map<String, Object> arguments(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    private static McpSchema.ToolAnnotations annotations(
            boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(openWorld)
                .build();
    }

    private static McpToolException toToolException(Throwable error) {
        if (error instanceof McpToolException exception) {
            return exception;
        }
        if (error instanceof AccessDeniedException) {
            return new McpToolException("FORBIDDEN", "The caller is not authorized", error);
        }
        if (error instanceof OptimisticLockingFailureException) {
            return new McpToolException("CONFLICT", "The resource changed; please retry", error);
        }
        if (error instanceof DataIntegrityViolationException) {
            return new McpToolException("CONFLICT", "The resource already exists", error);
        }
        if (error instanceof IllegalArgumentException) {
            var message = StringUtils.hasText(error.getMessage()) ? error.getMessage() : "Invalid argument";
            return new McpToolException("INVALID_ARGUMENT", message, error);
        }
        return new McpToolException("INTERNAL", "The built-in tool call failed", error);
    }

    record ToolPayload(Object data, String summary) {}
}
