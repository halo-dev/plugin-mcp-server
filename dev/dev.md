# Plugin Tool Provider Integration

Halo plugins can contribute tools through the protocol-neutral MCP Server API.
The integration is progressive: the provider plugin remains installable and
enabled when MCP Server is absent, and its tools become available when MCP Server
is installed and enabled.

## Add the API dependency

Use the API as a compile-only dependency. Do not package it or the MCP Java SDK
inside the provider plugin.

```groovy
dependencies {
    compileOnly "run.halo.mcpserver:api:1.0.0"
}
```

When developing both projects locally, build `:api:jar` and use the generated
`api/build/libs/api-1.0.0-SNAPSHOT.jar`, as demonstrated by the plugins
under [`examples/`](../examples/).

## Declare an optional plugin dependency

Add MCP Server to `plugin.yaml` with the `?` suffix. This lets Halo order and
reload the integration when MCP Server is present without preventing the
provider plugin from starting on its own.

```yaml
spec:
    requires: ">=2.26.0"
    pluginDependencies:
        mcp-server?: ">=1.0.0 & <2.0.0"
```

## Implement a provider

Implement `McpToolProvider` in a class that is not a Spring component. Tool names
must be globally unique and must use the owning plugin's `metadata.name` as the
namespace. MCP Server rejects a provider that claims another plugin's namespace.
Every tool requires a valid JSON Schema 2020-12 object input schema, a permission
callback, and a reactive handler. If `outputSchema` is declared, successful
structured results must conform to it.

```java
public final class ExportToolProvider implements McpToolProvider {
    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(McpToolDefinition.builder()
            .name("my-plugin/export")
            .title("Export data")
            .description("Export plugin data.")
            .displayTitle("导出数据")
            .displayDescription("导出插件数据。")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false))
            .annotations(McpToolAnnotations.readOnly("Export data"))
            .permission(invocation -> Mono.just(true))
            .handler(invocation -> Mono.just(
                McpToolResult.success(Map.of("items", List.of()))))
            .build());
    }
}
```

The key allowlist is checked before the provider callback. Returning `true` is
appropriate only when that allowlist is sufficient; otherwise, enforce the
plugin's additional domain rules in the callback. Return
`McpToolResult.error(code, message)` or throw `McpToolException` for expected
failures. Use stable codes such as `INVALID_ARGUMENT`, `NOT_FOUND`, or
`FORBIDDEN`.

`title` and `description` are sent to MCP clients and should be written for the
agent. Optional `displayTitle` and `displayDescription` values are shown in
Halo's MCP management UI, so a plugin can provide Chinese administrator-facing
copy without changing its protocol description. If omitted, the UI falls back
to the protocol values.

## Register the provider conditionally

Do not annotate the provider itself with `@Component`. Register it through a
configuration guarded by the API class name. The string-based condition is
required because the API class is unavailable when MCP Server is absent.

```java
@Configuration
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class McpToolConfiguration {
    @Bean
    ExportToolProvider exportToolProvider() {
        return new ExportToolProvider();
    }
}
```

MCP Server resolves enabled providers on each `tools/list` and `tools/call`.
Plugin lifecycle changes therefore take effect on the next request. A newly
contributed tool is denied to existing keys until an administrator selects it in
**Tools → MCP 服务**. Only tools selected for that key appear in `tools/list`.
Provider failures are isolated: an unavailable or invalid provider is logged and
omitted without hiding built-in tools or tools from healthy providers.

## Build and verify

Build the provider plugin normally, then inspect its JAR to confirm that
`run/halo/mcpserver/api` classes were not packaged. Verify both runtime states:

1. Enable the provider plugin without MCP Server; the plugin must start normally.
2. Enable MCP Server; the contributed tools must appear in **MCP 服务**.
3. Add a tool to a key and call `tools/list`; only selected tools must be listed.
4. Disable MCP Server; the provider plugin must remain enabled.

Complete implementations are available in
[`plugin-mcp-tool-demo`](../examples/plugin-mcp-tool-demo/) and
[`plugin-mcp-tool-clock`](../examples/plugin-mcp-tool-clock/).
