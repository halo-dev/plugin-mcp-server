package run.halo.mcpserver;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Bounds authenticated MCP work that is active in this plugin instance. */
@Component
final class McpRequestConcurrencyLimiter {

    static final int GLOBAL_LIMIT = 64;
    static final int PER_KEY_LIMIT = 8;

    private final int globalLimit;
    private final int perKeyLimit;
    private final Map<String, Integer> activeByKey = new HashMap<>();
    private int activeGlobal;

    McpRequestConcurrencyLimiter() {
        this(GLOBAL_LIMIT, PER_KEY_LIMIT);
    }

    McpRequestConcurrencyLimiter(int globalLimit, int perKeyLimit) {
        if (globalLimit < 1 || perKeyLimit < 1 || perKeyLimit > globalLimit) {
            throw new IllegalArgumentException("Invalid MCP concurrency limits");
        }
        this.globalLimit = globalLimit;
        this.perKeyLimit = perKeyLimit;
    }

    synchronized Optional<Permit> tryAcquire(String keyId) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        var activeForKey = activeByKey.getOrDefault(keyId, 0);
        if (activeGlobal >= globalLimit || activeForKey >= perKeyLimit) {
            return Optional.empty();
        }
        activeGlobal++;
        activeByKey.put(keyId, activeForKey + 1);
        return Optional.of(new Permit(this, keyId));
    }

    private synchronized void release(String keyId) {
        var activeForKey = activeByKey.get(keyId);
        if (activeForKey == null) {
            return;
        }
        activeGlobal--;
        if (activeForKey == 1) {
            activeByKey.remove(keyId);
        } else {
            activeByKey.put(keyId, activeForKey - 1);
        }
    }

    static final class Permit implements AutoCloseable {

        private final McpRequestConcurrencyLimiter limiter;
        private final String keyId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(McpRequestConcurrencyLimiter limiter, String keyId) {
            this.limiter = limiter;
            this.keyId = keyId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                limiter.release(keyId);
            }
        }
    }
}
