# MCP Tool Demo Plugin

This is a separate Halo plugin that contributes two tools through the
`run.halo.mcpserver.api.McpToolProvider` extension point:

- `mcp-tool-demo/hello`
- `mcp-tool-demo/word-count`

The MCP integration is optional. The API artifact is used only at compile time,
and the provider bean is created only when MCP Server is available. The plugin
therefore remains installable and enabled without MCP Server and does not
package the MCP Java SDK.

Build the API first, then build this plugin:

```bash
cd ../..
bash gradlew :api:jar
cd examples/plugin-mcp-tool-demo
bash ../../gradlew build
```

Install and enable the resulting JAR independently. If MCP Server is also
installed and enabled, add these tools to a key in **MCP 服务**; they will then
appear directly in that key's `tools/list` response.
