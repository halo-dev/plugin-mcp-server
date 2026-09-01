package run.halo.mcpserver.demo;

import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/** Example provider plugin; it does not depend on the MCP SDK. */
public class DemoMcpToolProvider implements McpToolProvider {

    private final AuthenticationTrustResolver authTrustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(hello(), wordCount());
    }

    private McpToolDefinition hello() {
        return McpToolDefinition.builder()
                .name("hello")
                .title("Say hello")
                .description("Return a greeting for a supplied name.")
                .displayTitle("生成问候语")
                .displayDescription("根据输入的姓名生成一条问候语。")
                .inputSchema(objectSchema(
                        Map.of("name", Map.of("type", "string", "minLength", 1)),
                        List.of("name")))
                .annotations(McpToolAnnotations.readOnly("Say hello"))
                .permission(this::authenticated)
                .handler(invocation -> {
                    var name = (String) invocation.arguments().get("name");
                    var data = Map.of("message", "Hello, " + name + "!");
                    return Mono.just(McpToolResult.success(data, "Generated a greeting"));
                })
                .build();
    }

    private McpToolDefinition wordCount() {
        return McpToolDefinition.builder()
                .name("word_count")
                .title("Count words")
                .description("Count words and characters in a supplied text.")
                .displayTitle("统计字数")
                .displayDescription("统计输入文本的单词数和字符数。")
                .inputSchema(objectSchema(
                        Map.of("text", Map.of("type", "string", "minLength", 1)),
                        List.of("text")))
                .annotations(McpToolAnnotations.readOnly("Count words"))
                .permission(this::authenticated)
                .handler(invocation -> {
                    var text = (String) invocation.arguments().get("text");
                    if (text == null || text.isBlank()) {
                        return Mono.error(new McpToolException(
                                "INVALID_ARGUMENT", "text must be a non-empty string"));
                    }
                    var words = text.trim().split("\\s+");
                    return Mono.just(McpToolResult.success(Map.of(
                            "words", words.length,
                            "characters", text.length())));
                })
                .build();
    }

    private Mono<Boolean> authenticated(McpToolInvocation invocation) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .map(authTrustResolver::isAuthenticated)
                .defaultIfEmpty(false);
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }
}
