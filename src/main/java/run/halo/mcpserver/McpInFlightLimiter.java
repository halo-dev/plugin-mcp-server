package run.halo.mcpserver;

import org.springframework.stereotype.Component;

/** Process-local bounds on concurrent authenticated MCP request work. */
@Component
class McpInFlightLimiter {

    static final int GLOBAL_LIMIT = 100;
    static final int PER_KEY_LIMIT = 16;
    static final int MAX_TRACKED_KEYS = 10_000;

    private final KeyedInFlightLimiter limiter =
            new KeyedInFlightLimiter(GLOBAL_LIMIT, PER_KEY_LIMIT, MAX_TRACKED_KEYS);

    /**
     * Reserves one global and per-key slot for the key, or returns null when either budget is
     * exhausted. The caller must close the permit exactly once when the request terminates.
     */
    KeyedInFlightLimiter.Lease tryAcquire(String keyId) {
        return limiter.tryAcquire(keyId, 1);
    }
}
