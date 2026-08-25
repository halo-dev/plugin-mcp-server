package run.halo.mcpserver;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Process-local bounds on concurrent authenticated MCP request work. */
@Component
class McpInFlightLimiter {

    static final int GLOBAL_LIMIT = 100;
    static final int PER_KEY_LIMIT = 16;
    private static final int MAX_TRACKED_KEYS = 10_000;

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
        var count = keyCounts.computeIfAbsent(keyId, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > PER_KEY_LIMIT) {
            count.decrementAndGet();
            globalCount.decrementAndGet();
            return null;
        }
        return new Permit(keyId);
    }

    final class Permit implements AutoCloseable {

        private final String keyId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(String keyId) {
            this.keyId = keyId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                globalCount.decrementAndGet();
                var count = keyCounts.get(keyId);
                if (count != null) {
                    count.decrementAndGet();
                }
            }
        }
    }
}
