package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class SinglePageToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    @Test
    void retriesPageStateChangeAfterReconcilerVersionConflict() {
        var stalePage = page(3L);
        var latestPage = page(4L);
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(stalePage), Mono.just(latestPage));
        when(client.update(stalePage))
                .thenReturn(Mono.error(new OptimisticLockingFailureException("version changed")));
        when(client.update(latestPage)).thenReturn(Mono.just(latestPage));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.setPublishState(Map.of("name", "about", "publish", false)))
                .assertNext(payload -> assertThat(payload.summary()).isEqualTo("Unpublished single page about"))
                .verifyComplete();

        verify(client, times(2)).fetch(SinglePage.class, "about");
        verify(client, times(2)).update(any(SinglePage.class));
    }

    @Test
    void doesNotAdvertiseReconcilerManagedVersionAsPagePrecondition() {
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);
        var publishTool = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(SinglePageTools.SET_PUBLISH_STATE))
                .findFirst()
                .orElseThrow();
        var properties = (Map<?, ?>) publishTool.specification().tool().inputSchema().get("properties");

        assertThat(properties.containsKey("expectedVersion")).isFalse();
    }

    @Test
    void updatesPageMetadataWithoutCreatingSnapshot() {
        var page = page(3L);
        page.getSpec().setTitle("Old title");
        when(client.fetch(SinglePage.class, "about")).thenReturn(Mono.just(page));
        when(client.update(page)).thenReturn(Mono.just(page));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "about", "title", "About us")))
                .assertNext(payload -> assertThat(page.getSpec().getTitle()).isEqualTo("About us"))
                .verifyComplete();

        verify(client, times(0)).create(any(run.halo.app.core.extension.content.Snapshot.class));
    }

    @Test
    void removesANewSnapshotWhenTheHeadChangedConcurrently() {
        var original = page(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("head");
        var latest = page(4L);
        latest.getSpec().setHeadSnapshot("other-head");
        var base = snapshot("base", "old");
        var createdSnapshot = new AtomicReference<Snapshot>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(original), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(base));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> {
            var snapshot = invocation.<Snapshot>getArgument(0);
            createdSnapshot.set(snapshot);
            return Mono.just(snapshot);
        });
        when(client.delete(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "about", "raw", "new")))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("content changed"))
                .verify();

        verify(client, never()).update(any(SinglePage.class));
        verify(client).delete(createdSnapshot.get());
    }

    @Test
    void rejectsAContentUpdateWhenTheReleaseChangedConcurrently() {
        var original = page(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("release");
        var latest = page(4L);
        latest.getSpec().setHeadSnapshot("head");
        latest.getSpec().setReleaseSnapshot("head");
        var createdSnapshot = new AtomicReference<Snapshot>();
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(original), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> {
            var snapshot = invocation.<Snapshot>getArgument(0);
            createdSnapshot.set(snapshot);
            return Mono.just(snapshot);
        });
        when(client.delete(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "about", "raw", "new")))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("content changed"))
                .verify();

        verify(client, never()).update(any(SinglePage.class));
        verify(client).delete(createdSnapshot.get());
    }

    @Test
    void keepsASnapshotReferencedBeforeAnUpdateWatcherFails() {
        var original = page(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("release");
        var latest = page(4L);
        latest.getSpec().setHeadSnapshot("head");
        latest.getSpec().setReleaseSnapshot("release");
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(original), Mono.just(latest), Mono.just(latest));
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(SinglePage.class))).thenAnswer(invocation -> {
            var updated = invocation.<SinglePage>getArgument(0);
            updated.getSpec().setReleaseSnapshot(updated.getSpec().getHeadSnapshot());
            updated.getSpec().setHeadSnapshot("later-head");
            return Mono.error(new IllegalStateException("watcher"));
        });
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "about", "raw", "new")))
                .expectErrorMessage("watcher")
                .verify();

        verify(client, never()).delete(any(Snapshot.class));
    }

    @Test
    void keepsTheNewSnapshotWhenTheContentUpdateIsCancelled() {
        var original = page(3L);
        original.getSpec().setBaseSnapshot("base");
        original.getSpec().setHeadSnapshot("head");
        original.getSpec().setReleaseSnapshot("head");
        when(authorization.username()).thenReturn(Mono.just("admin"));
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(original), Mono.never());
        when(client.fetch(Snapshot.class, "base")).thenReturn(Mono.just(snapshot("base", "old")));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.update(Map.of("name", "about", "raw", "new")))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(client, never()).delete(any(Snapshot.class));
    }

    private static SinglePage page(long version) {
        var page = new SinglePage();
        page.setMetadata(ToolSupport.metadata("about"));
        page.getMetadata().setVersion(version);
        page.setSpec(new SinglePage.SinglePageSpec());
        return page;
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
