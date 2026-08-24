package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@ExtendWith(MockitoExtension.class)
class TagToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    @Test
    void createsTagWithVisualMetadata() {
        when(client.create(any(Tag.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new TagTools(client, authorization);

        StepVerifier.create(tools.create(Map.of(
                        "name", "java",
                        "displayName", "Java",
                        "color", "#f89820",
                        "cover", "/upload/java.jpg")))
                .assertNext(payload -> assertThat(payload.data().toString())
                        .contains("name=java", "color=#f89820", "cover=/upload/java.jpg"))
                .verifyComplete();
    }

    @Test
    void rejectsInvalidTagColorBeforeCreate() {
        var tools = new TagTools(client, authorization);

        assertThatThrownBy(() -> tools.create(Map.of(
                        "name", "java",
                        "displayName", "Java",
                        "color", "orange")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("hex color");
    }
}
