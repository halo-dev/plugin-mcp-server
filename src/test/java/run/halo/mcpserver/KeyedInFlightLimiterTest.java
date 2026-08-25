package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedInFlightLimiterTest {

    private static final int GLOBAL_LIMIT = 8;
    private static final int PER_KEY_LIMIT = 2;
    private static final int MAX_TRACKED_KEYS = 4;

    @Test
    void enforcesAndReleasesBudgets() {
        var limiter = new KeyedInFlightLimiter(GLOBAL_LIMIT, PER_KEY_LIMIT, MAX_TRACKED_KEYS);
        var first = limiter.tryAcquire("key-one", 1);
        var second = limiter.tryAcquire("key-one", 1);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(limiter.tryAcquire("key-one", 1)).isNull();
        first.close();
        first.close();
        assertThat(limiter.tryAcquire("key-one", 1)).isNotNull();
        assertThat(limiter.tryAcquire("key-one", 1)).isNull();

        var globalLimiter = new KeyedInFlightLimiter(GLOBAL_LIMIT, GLOBAL_LIMIT, GLOBAL_LIMIT + 1);
        var global = new ArrayList<KeyedInFlightLimiter.Lease>();
        for (var i = 0; i < GLOBAL_LIMIT; i++) {
            global.add(globalLimiter.tryAcquire("global-" + i, 1));
        }
        assertThat(global).doesNotContainNull();
        assertThat(globalLimiter.tryAcquire("global-extra", 1)).isNull();
    }

    @Test
    void forgetsReleasedKeys() {
        var limiter = new KeyedInFlightLimiter(GLOBAL_LIMIT, PER_KEY_LIMIT, MAX_TRACKED_KEYS);

        for (var i = 0; i < MAX_TRACKED_KEYS + 1; i++) {
            var lease = limiter.tryAcquire("key-" + i, 1);
            assertThat(lease).isNotNull();
            lease.close();
        }
    }

    @Test
    void keepsPerKeyAccountingConsistentUnderConcurrentChurn() throws InterruptedException {
        var limiter = new KeyedInFlightLimiter(GLOBAL_LIMIT, PER_KEY_LIMIT, MAX_TRACKED_KEYS);
        var inFlight = new ConcurrentHashMap<String, AtomicInteger>();
        var maximum = new ConcurrentHashMap<String, AtomicInteger>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        for (var t = 0; t < 8; t++) {
            var thread = new Thread(() -> {
                try {
                    for (var i = 0; i < 2_000; i++) {
                        var key = "key-" + i % 4;
                        var lease = limiter.tryAcquire(key, 1);
                        if (lease == null) {
                            continue;
                        }
                        var count = inFlight.computeIfAbsent(key, ignored -> new AtomicInteger());
                        var current = count.incrementAndGet();
                        maximum.computeIfAbsent(key, ignored -> new AtomicInteger())
                                .accumulateAndGet(current, Math::max);
                        count.decrementAndGet();
                        lease.close();
                    }
                } catch (Throwable error) {
                    errors.add(error);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (var thread : threads) {
            thread.join();
        }

        assertThat(errors).isEmpty();
        assertThat(maximum.values())
                .allSatisfy(max -> assertThat(max.get()).isLessThanOrEqualTo(PER_KEY_LIMIT));
        for (var i = 0; i < 4; i++) {
            var lease = limiter.tryAcquire("key-" + i, PER_KEY_LIMIT);
            assertThat(lease).isNotNull();
            lease.close();
        }
    }
}
