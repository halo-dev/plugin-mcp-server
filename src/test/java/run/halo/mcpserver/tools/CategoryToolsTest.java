package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.CategoryParentMutationLock;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class CategoryToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    AtomicReference<CategoryParentMutationLock> storedLock;

    @BeforeEach
    void setUpLockStore() {
        storedLock = new AtomicReference<>();
        lenient()
                .when(client.fetch(
                        eq(CategoryParentMutationLock.class),
                        eq(CategoryParentMutationCoordinator.LOCK_NAME)))
                .thenAnswer(ignored -> Mono.defer(() ->
                        Mono.justOrEmpty(copyOf(storedLock.get()))));
        lenient()
                .when(client.create(any(CategoryParentMutationLock.class)))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    synchronized (storedLock) {
                        if (storedLock.get() != null) {
                            return Mono.error(new DataIntegrityViolationException("lock exists"));
                        }
                        var created = copyOf(
                                invocation.<CategoryParentMutationLock>getArgument(0));
                        created.getMetadata().setVersion(1L);
                        storedLock.set(created);
                        return Mono.just(copyOf(created));
                    }
                }));
        lenient()
                .when(client.update(any(CategoryParentMutationLock.class)))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    synchronized (storedLock) {
                        var current = storedLock.get();
                        var candidate = invocation.<CategoryParentMutationLock>getArgument(0);
                        if (current == null
                                || !current.getMetadata()
                                        .getVersion()
                                        .equals(candidate.getMetadata().getVersion())) {
                            return Mono.error(new OptimisticLockingFailureException(
                                    "lock version changed"));
                        }
                        var updated = copyOf(candidate);
                        updated.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
                        storedLock.set(updated);
                        return Mono.just(copyOf(updated));
                    }
                }));
    }

    @Test
    void createsChildCategoryWithDisplayMetadata() {
        var parent = category("parent", null, 1L);
        when(client.fetch(Category.class, "parent")).thenReturn(Mono.just(parent));
        when(client.create(any(Category.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = newTools();

        StepVerifier.create(tools.create(ToolSupport.map(
                        "name", "child",
                        "displayName", "Child",
                        "parent", "parent",
                        "cover", "/upload/category.jpg",
                        "hideFromList", true)))
                .assertNext(payload -> assertThat(payload.data().toString())
                        .contains("name=child", "parent=parent", "cover=/upload/category.jpg", "hideFromList=true"))
                .verifyComplete();
    }

    @Test
    void rejectsCategoryParentCycle() {
        var child = category("child", "current", 1L);
        when(client.fetch(Category.class, "child")).thenReturn(Mono.just(child));
        var tools = newTools();

        StepVerifier.create(tools.update(Map.of("name", "current", "parent", "child")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .hasMessageContaining("category cycle"))
                .verify();
    }

    @Test
    void preventsCycleFromConcurrentReparenting() {
        var stored = new ConcurrentHashMap<String, Category>();
        stored.put("a", category("a", null, 1L));
        stored.put("b", category("b", null, 1L));

        assertConcurrentReparentingBlocked(stored, "a", "b", "b", "a");
    }

    @Test
    void preventsLongerCycleFromConcurrentReparenting() {
        var stored = new ConcurrentHashMap<String, Category>();
        stored.put("a", category("a", null, 1L));
        stored.put("b", category("b", "a", 1L));
        stored.put("c", category("c", null, 1L));

        assertConcurrentReparentingBlocked(stored, "a", "c", "c", "b");
    }

    @Test
    void keepsCoordinationLockUntilCancelledWriteFinishes() {
        var stored = new ConcurrentHashMap<String, Category>();
        stored.put("a", category("a", null, 1L));
        stored.put("b", category("b", null, 1L));
        var updateDispatched = Sinks.<Void>one();
        var allowCommit = Sinks.<Void>one();
        when(client.fetch(eq(Category.class), anyString())).thenAnswer(invocation ->
                Mono.defer(() -> Mono.just(copyOf(stored.get(invocation.getArgument(1))))));
        when(client.update(any(Category.class))).thenAnswer(invocation -> {
            var updated = copyOf(invocation.<Category>getArgument(0));
            if (!updated.getMetadata().getName().equals("a")) {
                return commit(updated, stored);
            }
            return Mono.defer(() -> {
                updateDispatched.tryEmitEmpty();
                return allowCommit.asMono().then(commit(updated, stored));
            });
        });
        var firstTools = newTools();
        var secondTools = newTools();

        var first = firstTools.update(Map.of("name", "a", "parent", "b")).subscribe();
        StepVerifier.create(updateDispatched.asMono()).verifyComplete();
        first.dispose();

        var secondOutcome = secondTools
                .update(Map.of("name", "b", "parent", "a"))
                .materialize()
                .cache();
        var second = secondOutcome.subscribe();
        StepVerifier.create(Mono.delay(Duration.ofMillis(250))
                        .doOnNext(ignored -> {
                            assertThat(stored.get("a").getSpec().getParent()).isNull();
                            assertThat(stored.get("b").getSpec().getParent()).isNull();
                            allowCommit.tryEmitEmpty();
                        })
                        .then(secondOutcome))
                .assertNext(signal -> {
                    assertThat(signal.isOnError()).isTrue();
                    assertThat(signal.getThrowable()).hasMessageContaining("category cycle");
                    assertAcyclic(stored);
                })
                .verifyComplete();
        second.dispose();
    }

    private void assertConcurrentReparentingBlocked(
            ConcurrentHashMap<String, Category> stored,
            String firstName,
            String firstParent,
            String secondName,
            String secondParent) {
        var fetchPair = new FetchPair();
        when(client.fetch(eq(Category.class), anyString()))
                .thenAnswer(invocation -> fetchPair.fetch(invocation.getArgument(1), stored));
        when(client.update(any(Category.class))).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            var updated = copyOf(invocation.<Category>getArgument(0));
            stored.put(updated.getMetadata().getName(), updated);
            return copyOf(updated);
        }));
        var firstTools = newTools();
        var secondTools = newTools();

        var firstUpdate = firstTools.update(Map.of("name", firstName, "parent", firstParent))
                .thenReturn("updated-" + firstName)
                .onErrorResume(error -> Mono.just("error:" + error.getMessage()))
                .subscribeOn(Schedulers.parallel());
        var secondUpdate = secondTools.update(Map.of("name", secondName, "parent", secondParent))
                .thenReturn("updated-" + secondName)
                .onErrorResume(error -> Mono.just("error:" + error.getMessage()))
                .subscribeOn(Schedulers.parallel());

        StepVerifier.create(Flux.merge(firstUpdate, secondUpdate).collectList())
                .assertNext(outcomes -> {
                    assertThat(outcomes).filteredOn(value -> value.startsWith("updated-")).hasSize(1);
                    assertThat(outcomes).anySatisfy(value -> assertThat(value)
                            .startsWith("error:")
                            .contains("category cycle"));
                    assertAcyclic(stored);
                })
                .verifyComplete();
    }

    private static void assertAcyclic(Map<String, Category> stored) {
        stored.keySet().forEach(name -> {
            var visited = new HashSet<String>();
            var current = name;
            while (current != null) {
                assertThat(visited.add(current)).as("parent chain from %s", name).isTrue();
                var category = stored.get(current);
                current = category == null ? null : category.getSpec().getParent();
            }
        });
    }

    private static Category category(String name, String parent, long version) {
        var category = new Category();
        category.setMetadata(ToolSupport.metadata(name));
        category.getMetadata().setVersion(version);
        category.setSpec(new Category.CategorySpec());
        category.getSpec().setDisplayName(name);
        category.getSpec().setSlug(name);
        category.getSpec().setParent(parent);
        category.getSpec().setPriority(0);
        return category;
    }

    private static Category copyOf(Category source) {
        return category(
                source.getMetadata().getName(),
                source.getSpec().getParent(),
                source.getMetadata().getVersion());
    }

    private CategoryTools newTools() {
        return new CategoryTools(
                client, authorization, new CategoryParentMutationCoordinator(client));
    }

    private static Mono<Category> commit(
            Category updated, ConcurrentHashMap<String, Category> stored) {
        return Mono.fromSupplier(() -> {
            var committed = copyOf(updated);
            stored.put(committed.getMetadata().getName(), committed);
            return copyOf(committed);
        });
    }

    private static CategoryParentMutationLock copyOf(CategoryParentMutationLock source) {
        if (source == null) {
            return null;
        }
        var copy = new CategoryParentMutationLock();
        copy.setMetadata(ToolSupport.metadata(source.getMetadata().getName()));
        copy.getMetadata().setVersion(source.getMetadata().getVersion());
        var spec = new CategoryParentMutationLock.Spec();
        spec.setHolder(source.getSpec().getHolder());
        spec.setExpiresAt(source.getSpec().getExpiresAt());
        spec.setGeneration(source.getSpec().getGeneration());
        copy.setSpec(spec);
        return copy;
    }

    private static final class FetchPair {
        private static final Duration WAIT_FOR_CONCURRENT_FETCH = Duration.ofMillis(250);

        private final Object monitor = new Object();
        private Sinks.One<Void> release = Sinks.one();
        private int arrivals;

        Mono<Category> fetch(String name, Map<String, Category> stored) {
            return Mono.defer(() -> {
                var snapshot = copyOf(stored.get(name));
                Sinks.One<Void> currentRelease;
                synchronized (monitor) {
                    currentRelease = release;
                    arrivals++;
                    if (arrivals == 2) {
                        arrivals = 0;
                        release = Sinks.one();
                        currentRelease.tryEmitEmpty();
                    }
                }
                return currentRelease
                        .asMono()
                        .timeout(WAIT_FOR_CONCURRENT_FETCH)
                        .onErrorResume(TimeoutException.class, ignored -> Mono.empty())
                        .thenReturn(snapshot);
            });
        }
    }
}
