package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Duration;
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
    void createsPostWithEditorialMetadata() {
        var storedPost = new AtomicReference<Post>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.create(any(Post.class))).thenAnswer(invocation -> {
            var post = invocation.<Post>getArgument(0);
            storedPost.set(post);
            return Mono.just(post);
        });
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.fetch(Post.class, "featured-post"))
                .thenAnswer(invocation -> Mono.just(storedPost.get()));
        when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.create(ToolSupport.map(
                        "name", "featured-post",
                        "title", "Featured post",
                        "raw", "Body",
                        "cover", "/upload/cover.jpg",
                        "template", "post-featured",
                        "excerpt", "Manual summary",
                        "pinned", true,
                        "priority", 20,
                        "publishTime", "2026-08-24T00:00:00Z")))
                .assertNext(payload -> {
                    var spec = storedPost.get().getSpec();
                    assertThat(spec.getCover()).isEqualTo("/upload/cover.jpg");
                    assertThat(spec.getTemplate()).isEqualTo("post-featured");
                    assertThat(spec.getExcerpt().getRaw()).isEqualTo("Manual summary");
                    assertThat(spec.getExcerpt().getAutoGenerate()).isFalse();
                    assertThat(spec.getPinned()).isTrue();
                    assertThat(spec.getPriority()).isEqualTo(20);
                    assertThat(spec.getPublishTime()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"));
                })
                .verifyComplete();
    }

    @Test
    void removesTheCreatedPostWhenItsInitialSnapshotFails() {
        var post = post(1L);
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.create(any(Post.class))).thenReturn(Mono.just(post));
        when(client.create(any(Snapshot.class))).thenReturn(Mono.error(new IllegalStateException("snapshot")));
        when(client.delete(post)).thenReturn(Mono.just(post));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.create(Map.of(
                        "name", "hello-world", "title", "Hello", "raw", "Body")))
                .expectErrorMessage("snapshot")
                .verify();

        verify(client).delete(post);
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

        StepVerifier.create(tools.setPublishState(Map.of("name", "hello-world", "publish", true)))
                .assertNext(payload -> assertThat(payload.summary()).isEqualTo("Published post hello-world"))
                .verifyComplete();

        verify(client, times(2)).fetch(Post.class, "hello-world");
        verify(client, times(2)).update(any(Post.class));
    }

    @Test
    void updatesEditorialMetadataWithoutCreatingSnapshot() {
        var post = post(3L);
        post.getSpec().setCover("old.jpg");
        when(client.fetch(Post.class, "hello-world")).thenReturn(Mono.just(post));
        when(client.update(post)).thenReturn(Mono.just(post));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of(
                        "name", "hello-world",
                        "cover", "new.jpg",
                        "excerpt", "Short summary")))
                .assertNext(payload -> {
                    assertThat(post.getSpec().getCover()).isEqualTo("new.jpg");
                    assertThat(post.getSpec().getExcerpt().getRaw()).isEqualTo("Short summary");
                    assertThat(post.getSpec().getExcerpt().getAutoGenerate()).isFalse();
                })
                .verifyComplete();

        verify(client, times(1)).update(any(Post.class));
        verify(client, times(0)).create(any(Snapshot.class));
    }

    @Test
    void rejectsAContentUpdateWhenTheHeadChangedConcurrently() {
        var original = post(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("head");
        var latest = post(4L);
        latest.getSpec().setHeadSnapshot("other-head");
        var base = snapshot("base", "old");
        var createdSnapshot = new AtomicReference<Snapshot>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(Post.class, "hello-world"))
                .thenReturn(Mono.just(original), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(base));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> {
            var snapshot = invocation.<Snapshot>getArgument(0);
            createdSnapshot.set(snapshot);
            return Mono.just(snapshot);
        });
        when(client.delete(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "hello-world", "raw", "new")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .hasMessageContaining("content changed"))
                .verify();

        verify(client, never()).update(any(Post.class));
        verify(client).delete(createdSnapshot.get());
    }

    @Test
    void rejectsAContentUpdateWhenTheReleaseChangedConcurrently() {
        var original = post(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("release");
        var latest = post(4L);
        latest.getSpec().setHeadSnapshot("head");
        latest.getSpec().setReleaseSnapshot("head");
        var createdSnapshot = new AtomicReference<Snapshot>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(Post.class, "hello-world"))
                .thenReturn(Mono.just(original), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> {
            var snapshot = invocation.<Snapshot>getArgument(0);
            createdSnapshot.set(snapshot);
            return Mono.just(snapshot);
        });
        when(client.delete(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "hello-world", "raw", "new")))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("content changed"))
                .verify();

        verify(client, never()).update(any(Post.class));
        verify(client).delete(createdSnapshot.get());
    }

    @Test
    void keepsASnapshotReferencedBeforeAnUpdateWatcherFails() {
        var original = post(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("release");
        var latest = post(4L);
        latest.getSpec().setHeadSnapshot("head");
        latest.getSpec().setReleaseSnapshot("release");
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(Post.class, "hello-world"))
                .thenReturn(Mono.just(original), Mono.just(latest), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(Post.class))).thenAnswer(invocation -> {
            var updated = invocation.<Post>getArgument(0);
            updated.getSpec().setReleaseSnapshot(updated.getSpec().getHeadSnapshot());
            updated.getSpec().setHeadSnapshot("later-head");
            return Mono.error(new IllegalStateException("watcher"));
        });
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "hello-world", "raw", "new")))
                .expectErrorMessage("watcher")
                .verify();

        verify(client, never()).delete(any(Snapshot.class));
    }

    @Test
    void keepsTheNewSnapshotWhenTheContentUpdateIsCancelled() {
        var original = post(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("head");
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(Post.class, "hello-world"))
                .thenReturn(Mono.just(original), Mono.never());
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "hello-world", "raw", "new")))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(client, never()).delete(any(Snapshot.class));
    }

    @Test
    void doesNotAdvertiseReconcilerManagedVersionAsPostPrecondition() {
        var tools = new PostTools(client, contentService, new ContentSnapshots(client), authorization);
        var publishTool = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(PostTools.SET_PUBLISH_STATE))
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

    private static Snapshot snapshot(String name, String raw) {
        var snapshot = new Snapshot();
        snapshot.setMetadata(ToolSupport.metadata(name));
        var spec = new Snapshot.SnapShotSpec();
        spec.setRawPatch(raw);
        spec.setContentPatch(raw);
        snapshot.setSpec(spec);
        return snapshot;
    }
}
