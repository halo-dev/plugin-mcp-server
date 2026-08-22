package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
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
        var storedPost = new AtomicReference<Post>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.create(any(Post.class))).thenAnswer(invocation -> {
            var post = invocation.<Post>getArgument(0);
            storedPost.set(post);
            return Mono.just(post);
        });
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.fetch(Post.class, "hello-world"))
                .thenAnswer(invocation -> Mono.just(storedPost.get()));
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
    void retriesPostStateChangeAfterReconcilerVersionConflict() {
        var stalePost = post(3L);
        var latestPost = post(4L);
        when(client.fetch(Post.class, "hello-world"))
                .thenReturn(Mono.just(stalePost), Mono.just(latestPost));
        when(client.update(stalePost))
                .thenReturn(Mono.error(new OptimisticLockingFailureException("version changed")));
        when(client.update(latestPost)).thenReturn(Mono.just(latestPost));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.publish(Map.of("name", "hello-world")))
                .assertNext(payload -> assertThat(payload.summary()).isEqualTo("Published post hello-world"))
                .verifyComplete();

        verify(client, times(2)).fetch(Post.class, "hello-world");
        verify(client, times(2)).update(any(Post.class));
    }

    @Test
    void doesNotAdvertiseReconcilerManagedVersionAsPostPrecondition() {
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);
        var publishTool = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(PostTools.PUBLISH_POST))
                .findFirst()
                .orElseThrow();
        var properties = (Map<?, ?>) publishTool.specification().tool().inputSchema().get("properties");

        assertThat(properties.containsKey("expectedVersion")).isFalse();
    }

    private static Post post(long version) {
        var post = new Post();
        post.setMetadata(ToolSupport.metadata("hello-world"));
        post.getMetadata().setVersion(version);
        post.setSpec(new Post.PostSpec());
        return post;
    }
}
