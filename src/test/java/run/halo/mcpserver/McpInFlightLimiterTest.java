package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpInFlightLimiterTest {

    @Test
    void enforcesThePerKeyLimitIndependently() {
        var limiter = new McpInFlightLimiter();
        var permits = new ArrayList<McpInFlightLimiter.Permit>();

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            permits.add(limiter.tryAcquire("key-one"));
        }
        assertThat(limiter.tryAcquire("key-one")).isNull();
        assertThat(limiter.tryAcquire("key-two")).isNotNull();

        permits.getFirst().close();
        assertThat(limiter.tryAcquire("key-one")).isNotNull();
    }

    @Test
    void enforcesTheGlobalLimitAcrossKeys() {
        var limiter = new McpInFlightLimiter();
        var permits = new ArrayList<McpInFlightLimiter.Permit>();

        var key = 0;
        while (permits.size() < McpInFlightLimiter.GLOBAL_LIMIT) {
            var permit = limiter.tryAcquire("key-" + key++);
            assertThat(permit).isNotNull();
            permits.add(permit);
        }
        assertThat(limiter.tryAcquire("key-extra")).isNull();

        permits.forEach(McpInFlightLimiter.Permit::close);
        for (var i = 0; i < McpInFlightLimiter.GLOBAL_LIMIT; i++) {
            assertThat(limiter.tryAcquire("key-reused-" + i)).isNotNull();
        }
    }

    @Test
    void forgetsKeysWhosePermitsHaveAllBeenReleased() {
        var limiter = new McpInFlightLimiter();

        for (var i = 0; i < McpInFlightLimiter.MAX_TRACKED_KEYS + 1; i++) {
            var permit = limiter.tryAcquire("key-" + i);
            assertThat(permit).isNotNull();
            permit.close();
        }

        assertThat(limiter.tryAcquire("key-fresh")).isNotNull();
    }

    @Test
    void keepsPerKeyAccountingConsistentUnderConcurrentChurn() throws InterruptedException {
        var limiter = new McpInFlightLimiter();
        // The test counters bracket acquire and close so they always overestimate the true
        // in-flight count; exceeding the limit here therefore proves a real breach.
        var inFlightPerKey = new ConcurrentHashMap<String, AtomicInteger>();
        var maxPerKey = new ConcurrentHashMap<String, AtomicInteger>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        for (var t = 0; t < 8; t++) {
            var thread = new Thread(() -> {
                try {
                    for (var i = 0; i < 2_000; i++) {
                        var key = "key-" + i % 4;
                        var permit = limiter.tryAcquire(key);
                        if (permit == null) {
                            continue;
                        }
                        var inFlight = inFlightPerKey
                                .computeIfAbsent(key, ignored -> new AtomicInteger())
                                .incrementAndGet();
                        maxPerKey.computeIfAbsent(key, ignored -> new AtomicInteger())
                                .accumulateAndGet(inFlight, Math::max);
                        inFlightPerKey.get(key).decrementAndGet();
                        permit.close();
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
        assertThat(maxPerKey.values())
                .allSatisfy(max -> assertThat(max.get())
                        .isLessThanOrEqualTo(McpInFlightLimiter.PER_KEY_LIMIT));
        // Both budgets must drain back to zero once every permit is closed.
        for (var i = 0; i < 4; i++) {
            var permits = new ArrayList<McpInFlightLimiter.Permit>();
            for (var j = 0; j < McpInFlightLimiter.PER_KEY_LIMIT; j++) {
                var permit = limiter.tryAcquire("key-" + i);
                assertThat(permit).isNotNull();
                permits.add(permit);
            }
            assertThat(limiter.tryAcquire("key-" + i)).isNull();
            permits.forEach(McpInFlightLimiter.Permit::close);
        }
    }

    @Test
    void ignoresDoubleRelease() {
        var limiter = new McpInFlightLimiter();
        var permit = limiter.tryAcquire("key-one");
        assertThat(permit).isNotNull();

        permit.close();
        permit.close();

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            assertThat(limiter.tryAcquire("key-one")).isNotNull();
        }
        assertThat(limiter.tryAcquire("key-one")).isNull();
    }
}
