package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class PostToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    PostContentService contentService;

    @Mock
    McpAuthorization authorization;

    @Test
    void createsPostAndInitialBaseSnapshot() {
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.create(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.create(Map.of(
                        "name", "hello-world",
                        "title", "Hello world",
                        "raw", "# Hello",
                        "content", "<h1>Hello</h1>")))
                .assertNext(payload -> assertThat(payload.data().toString())
                        .contains("name=hello-world", "baseSnapshot=", "headSnapshot="))
                .verifyComplete();
    }

    @Test
    void rejectsStalePostStateChange() {
        var post = new Post();
        post.setMetadata(ToolSupport.metadata("hello-world"));
        post.getMetadata().setVersion(3L);
        post.setSpec(new Post.PostSpec());
        when(client.fetch(Post.class, "hello-world")).thenReturn(Mono.just(post));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.publish(Map.of("name", "hello-world", "expectedVersion", 2)))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("expected version 2"))
                .verify();
    }
}
