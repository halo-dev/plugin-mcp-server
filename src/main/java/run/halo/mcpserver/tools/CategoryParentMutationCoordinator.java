package run.halo.mcpserver.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.CategoryParentMutationLock;

@Component
class CategoryParentMutationCoordinator {

    static final String LOCK_NAME = "category-parent-mutations";

    private static final Logger log =
            LoggerFactory.getLogger(CategoryParentMutationCoordinator.class);
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    private static final Duration LOCK_RENEW_INTERVAL = Duration.ofMinutes(1);
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    private final ReactiveExtensionClient client;

    CategoryParentMutationCoordinator(ReactiveExtensionClient client) {
        this.client = client;
    }

    <T> Mono<T> serialize(Supplier<Mono<T>> mutation) {
        return Mono.defer(() -> acquire(UUID.randomUUID().toString())
                .flatMap(permit -> runToCompletion(permit, mutation)));
    }

    private Mono<Permit> acquire(String holder) {
        return getOrCreateLock().flatMap(lock -> {
            var now = Instant.now();
            var spec = lock.getSpec();
            if (StringUtils.hasText(spec.getHolder())
                    && spec.getExpiresAt() != null
                    && spec.getExpiresAt().isAfter(now)) {
                return retryAcquire(holder);
            }
            spec.setHolder(holder);
            spec.setExpiresAt(now.plus(LOCK_LEASE));
            spec.setGeneration(spec.getGeneration() + 1);
            return client.update(lock)
                    .thenReturn(new Permit(holder))
                    .onErrorResume(this::isContention, ignored -> retryAcquire(holder));
        });
    }

    private Mono<Permit> retryAcquire(String holder) {
        return Mono.delay(RETRY_DELAY).then(Mono.defer(() -> acquire(holder)));
    }

    private Mono<CategoryParentMutationLock> getOrCreateLock() {
        return client.fetch(CategoryParentMutationLock.class, LOCK_NAME)
                .switchIfEmpty(Mono.defer(() -> client.create(newLock())
                        .onErrorResume(this::isContention, ignored -> Mono.empty())
                        .then(Mono.defer(() -> client.fetch(
                                CategoryParentMutationLock.class, LOCK_NAME)))
                        .switchIfEmpty(Mono.delay(RETRY_DELAY)
                                .then(Mono.defer(this::getOrCreateLock)))));
    }

    private <T> Mono<T> runToCompletion(Permit permit, Supplier<Mono<T>> mutation) {
        return Mono.usingWhen(
                        Mono.just(permit),
                        ignored -> Mono.using(
                                () -> renewLease(permit),
                                renewal -> Mono.defer(mutation),
                                Disposable::dispose),
                        this::release,
                        (ignored, error) -> release(permit),
                        this::release)
                .cache();
    }

    private Disposable renewLease(Permit permit) {
        return Flux.interval(LOCK_RENEW_INTERVAL)
                .concatMap(ignored -> extendLease(permit)
                        .doOnError(error -> log.warn(
                                "Failed to renew the category parent mutation lock",
                                error))
                        .onErrorComplete())
                .subscribe();
    }

    private Mono<Void> extendLease(Permit permit) {
        return Mono.defer(() -> client.fetch(CategoryParentMutationLock.class, LOCK_NAME)
                        .flatMap(lock -> {
                            var spec = lock.getSpec();
                            if (!permit.holder().equals(spec.getHolder())) {
                                return Mono.empty();
                            }
                            spec.setExpiresAt(Instant.now().plus(LOCK_LEASE));
                            return client.update(lock).then();
                        }))
                .retryWhen(contentionRetry());
    }

    private Mono<Void> release(Permit permit) {
        return Mono.defer(() -> client.fetch(CategoryParentMutationLock.class, LOCK_NAME)
                        .flatMap(lock -> {
                            var spec = lock.getSpec();
                            if (!permit.holder().equals(spec.getHolder())) {
                                return Mono.empty();
                            }
                            spec.setHolder(null);
                            spec.setExpiresAt(null);
                            return client.update(lock).then();
                        }))
                .retryWhen(contentionRetry())
                .doOnError(error -> log.warn(
                        "Failed to release the category parent mutation lock; its lease will expire",
                        error))
                .onErrorComplete();
    }

    private Retry contentionRetry() {
        return Retry.backoff(3, Duration.ofMillis(25))
                .maxBackoff(Duration.ofMillis(100))
                .filter(this::isContention)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isContention(Throwable error) {
        return error instanceof OptimisticLockingFailureException
                || error instanceof DataIntegrityViolationException;
    }

    private static CategoryParentMutationLock newLock() {
        var lock = new CategoryParentMutationLock();
        lock.setMetadata(ToolSupport.metadata(LOCK_NAME));
        return lock;
    }

    private record Permit(String holder) {}
}
