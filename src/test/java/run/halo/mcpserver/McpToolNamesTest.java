package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolResult;

class McpToolNamesTest {

    @Test
    void generatesClientSafeNamesAndRecoversThePluginOwner() {
        var name = McpToolNames.contributed("PluginMoments", "list_moments");

        assertThat(name).isEqualTo("PluginMoments__list_moments");
        assertThat(name).matches("[A-Za-z0-9_-]{1,128}");
        assertThat(McpToolNames.pluginName(name)).contains("PluginMoments");
    }

    @Test
    void encodesPluginIdsWithoutCollisions() {
        var dotted = McpToolNames.contributed("example.foo", "search_posts");
        var underscored = McpToolNames.contributed("example_00002efoo", "search_posts");

        assertThat(dotted).isEqualTo("example_00002efoo__search_posts");
        assertThat(underscored).isNotEqualTo(dotted);
        assertThat(McpToolNames.pluginName(dotted)).contains("example.foo");
        assertThat(McpToolNames.pluginName(underscored)).contains("example_00002efoo");
    }

    @Test
    void rejectsNamesOutsideTheSharedContract() {
        assertThat(McpToolNames.pluginName("demo__ListPosts")).isEmpty();
        assertThat(McpToolNames.pluginName("demo/list_posts")).isEmpty();
        assertThatThrownBy(() -> McpToolNames.contributed("demo", "ListPosts"))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> McpToolNames.contributed("x".repeat(120), "list_posts"))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void keepsBothNameComponentsWithinTheProtocolLimit() {
        assertThat(McpToolNames.contributed("p".repeat(63), "t".repeat(63))).hasSize(128);
        assertThatThrownBy(() -> McpToolNames.contributed("p".repeat(64), "tool"))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> tool("t".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    private static McpToolDefinition tool(String name) {
        return McpToolDefinition.builder()
                .name(name)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .permission(invocation -> Mono.just(true))
                .handler(invocation -> Mono.just(McpToolResult.success(Map.of())))
                .build();
    }
}
