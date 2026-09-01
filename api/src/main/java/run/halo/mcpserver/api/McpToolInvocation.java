package run.halo.mcpserver.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-scoped data passed to a contributed tool. {@link #toolName()} is the local provider name. */
public final class McpToolInvocation {

    private final String toolName;
    private final Map<String, Object> arguments;

    public McpToolInvocation(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName;
        this.arguments = arguments == null || arguments.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }
}
