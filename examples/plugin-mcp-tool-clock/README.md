# MCP Tool Clock Plugin

This second provider plugin contributes `mcp-tool-clock__current_time` through
the Halo MCP Server API. It is intentionally independent from the demo plugin
so the runtime verification covers multiple provider plugin class loaders.

Its MCP Server dependency is optional and its API dependency is compile-only.
The plugin can remain installed and enabled without MCP Server; the tool provider
bean is created automatically when MCP Server is available.
