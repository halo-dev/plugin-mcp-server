package run.halo.mcpserver;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Process-local bounds on concurrent authenticated MCP request work. */
@Component
class McpInFlightLimiter {

    static final int GLOBAL_LIMIT = 100;
    static final int PER_KEY_LIMIT = 16;
    static final int MAX_TRACKED_KEYS = 10_000;

    private final AtomicInteger globalCount = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> keyCounts = new ConcurrentHashMap<>();

    /**
     * Reserves one global and per-key slot for the key, or returns null when either budget is
     * exhausted. The caller must close the permit exactly once when the request terminates.
     */
    Permit tryAcquire(String keyId) {
        if (keyCounts.size() >= MAX_TRACKED_KEYS && !keyCounts.containsKey(keyId)) {
            return null;
        }
        if (globalCount.incrementAndGet() > GLOBAL_LIMIT) {
            globalCount.decrementAndGet();
            return null;
        }
        // Create-or-increment and the limit check run inside the key's map bin so a permit can
        // never land on a counter that a concurrent release has already unmapped.
        var acquired = new AtomicReference<AtomicInteger>();
        keyCounts.compute(keyId, (key, existing) -> {
            var count = existing == null ? new AtomicInteger() : existing;
            if (count.incrementAndGet() > PER_KEY_LIMIT) {
                count.decrementAndGet();
                return existing;
            }
            acquired.set(count);
            return count;
        });
        var count = acquired.get();
        if (count == null) {
            globalCount.decrementAndGet();
            return null;
        }
        return new Permit(keyId, count);
    }

    final class Permit implements AutoCloseable {

        private final String keyId;
        private final AtomicInteger count;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(String keyId, AtomicInteger count) {
            this.keyId = keyId;
            this.count = count;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                globalCount.decrementAndGet();
                if (count.decrementAndGet() <= 0) {
                    // Drop the entry once the key holds no permits so churn through many keys
                    // cannot permanently exhaust MAX_TRACKED_KEYS. The identity and zero
                    // rechecks keep a racing acquisition from losing its counter.
                    keyCounts.computeIfPresent(keyId,
                            (key, mapped) -> mapped == count && mapped.get() <= 0 ? null : mapped);
                }
            }
        }
    }
}
