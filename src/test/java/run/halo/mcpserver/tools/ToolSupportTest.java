package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.mcpserver.McpAuthorization;

class ToolSupportTest {

    @Test
    void includesStructuredDataAsJsonTextForCompatibility() {
        var authorization = mock(McpAuthorization.class);
        when(authorization.authorize(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
        var support = new TestToolSupport(authorization);
        var tool = support.tool(
                "test_list_posts",
                "List posts",
                "List posts.",
                "查询文章",
                "查询文章。",
                "TEST",
                ToolSupport.objectSchema(Map.of(), List.of()),
                ToolSupport.objectSchema(Map.of(), List.of()),
                ToolSupport.READ_ONLY,
                arguments -> Mono.just(ToolSupport.payload(
                        Map.of("items", List.of(Map.of("title", "Hello"))),
                        "Listed 1 posts")));

        var result = tool.specification()
                .callHandler()
                .apply(
                        McpTransportContext.EMPTY,
                        McpSchema.CallToolRequest.builder(tool.protocolTool().name())
                                .arguments(Map.of())
                                .build());

        StepVerifier.create(result)
                .assertNext(callResult -> {
                    assertThat(callResult.content()).hasSize(2);
                    assertThat(callResult.content().get(0))
                            .isInstanceOfSatisfying(
                                    McpSchema.TextContent.class,
                                    content -> assertThat(content.text())
                                            .isEqualTo("{\"items\":[{\"title\":\"Hello\"}]}"));
                    assertThat(callResult.content().get(1))
                            .isInstanceOfSatisfying(
                                    McpSchema.TextContent.class,
                                    content -> assertThat(content.text()).isEqualTo("Listed 1 posts"));
                    assertThat(callResult.structuredContent())
                            .isEqualTo(Map.of("items", List.of(Map.of("title", "Hello"))));
                })
                .verifyComplete();
    }

    private static final class TestToolSupport extends ToolSupport {

        private TestToolSupport(McpAuthorization authorization) {
            super(authorization);
        }
    }
}
