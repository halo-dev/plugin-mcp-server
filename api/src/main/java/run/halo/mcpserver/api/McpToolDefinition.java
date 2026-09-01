package run.halo.mcpserver.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Protocol-neutral definition of one tool contributed by a Halo plugin. */
public final class McpToolDefinition {

    private final String name;
    private final String title;
    private final String description;
    private final String displayTitle;
    private final String displayDescription;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final McpToolAnnotations annotations;
    private final McpToolPermission permission;
    private final McpToolHandler handler;

    private McpToolDefinition(Builder builder) {
        if (builder.name == null || builder.name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (builder.name.length() > 63
                || !builder.name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Tool name must be a lowercase snake_case local name with at most 63 characters");
        }
        this.name = builder.name;
        this.title = builder.title;
        this.description = Objects.requireNonNullElse(builder.description, "");
        this.displayTitle = builder.displayTitle == null ? this.title : builder.displayTitle;
        this.displayDescription = Objects.requireNonNullElse(builder.displayDescription, this.description);
        this.inputSchema = copySchema(builder.inputSchema);
        this.outputSchema = builder.outputSchema == null ? null : copySchema(builder.outputSchema);
        this.annotations = Objects.requireNonNullElse(
                builder.annotations, McpToolAnnotations.defaults(builder.title));
        this.permission = Objects.requireNonNull(builder.permission,
                "A tool must declare a permission callback");
        this.handler = Objects.requireNonNull(builder.handler, "A tool must declare a handler");
    }

    private static Map<String, Object> copySchema(Map<String, Object> schema) {
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Tool schemas must be JSON objects");
        }
        return Map.copyOf(new LinkedHashMap<>(schema));
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    /** Title shown in Halo's MCP management UI. Defaults to the protocol title. */
    public String displayTitle() {
        return displayTitle;
    }

    /** Description shown in Halo's MCP management UI. Defaults to the protocol description. */
    public String displayDescription() {
        return displayDescription;
    }

    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    public Map<String, Object> outputSchema() {
        return outputSchema;
    }

    public McpToolAnnotations annotations() {
        return annotations;
    }

    public McpToolPermission permission() {
        return permission;
    }

    public McpToolHandler handler() {
        return handler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String title;
        private String description;
        private String displayTitle;
        private String displayDescription;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private McpToolAnnotations annotations;
        private McpToolPermission permission;
        private McpToolHandler handler;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder displayTitle(String displayTitle) {
            this.displayTitle = displayTitle;
            return this;
        }

        public Builder displayDescription(String displayDescription) {
            this.displayDescription = displayDescription;
            return this;
        }

        public Builder inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder outputSchema(Map<String, Object> outputSchema) {
            this.outputSchema = outputSchema;
            return this;
        }

        public Builder annotations(McpToolAnnotations annotations) {
            this.annotations = annotations;
            return this;
        }

        public Builder permission(McpToolPermission permission) {
            this.permission = permission;
            return this;
        }

        public Builder handler(McpToolHandler handler) {
            this.handler = handler;
            return this;
        }

        public McpToolDefinition build() {
            return new McpToolDefinition(this);
        }
    }
}
