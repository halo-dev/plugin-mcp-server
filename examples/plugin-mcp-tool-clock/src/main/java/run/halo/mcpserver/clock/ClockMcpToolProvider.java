package run.halo.mcpserver.clock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

public class ClockMcpToolProvider implements McpToolProvider {

    private final AuthenticationTrustResolver authTrustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(McpToolDefinition.builder()
                .name("mcp-tool-clock/current-time")
                .title("Current time")
                .description("Return the current server time in UTC.")
                .displayTitle("查询服务器时间")
                .displayDescription("返回服务器当前的 UTC 时间。")
                .inputSchema(objectSchema())
                .annotations(McpToolAnnotations.readOnly("Current time"))
                .permission(this::authenticated)
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of(
                        "utc", Instant.now().toString()))))
                .build());
    }

    private Mono<Boolean> authenticated(McpToolInvocation invocation) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .map(authTrustResolver::isAuthenticated)
                .defaultIfEmpty(false);
    }

    private static Map<String, Object> objectSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
    }
}
