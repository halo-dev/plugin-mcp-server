package run.halo.mcpserver.tools;

import org.springframework.stereotype.Component;
import run.halo.mcpserver.KeyedInFlightLimiter;

/** Process-local in-flight byte budgets that bound concurrent attachment uploads. */
@Component
class AttachmentUploadLimiter {

    static final int GLOBAL_BUDGET_BYTES = 64 * 1024 * 1024;
    static final int PER_KEY_BUDGET_BYTES = 32 * 1024 * 1024;
    static final int MAX_TRACKED_KEYS = 10_000;

    private final KeyedInFlightLimiter limiter =
            new KeyedInFlightLimiter(GLOBAL_BUDGET_BYTES, PER_KEY_BUDGET_BYTES, MAX_TRACKED_KEYS);

    /**
     * Reserves decoded bytes against the global and per-key budgets, or returns null when either
     * is exhausted. The caller must close the reservation exactly once when the upload terminates.
     */
    KeyedInFlightLimiter.Lease tryAcquire(String keyId, int bytes) {
        return limiter.tryAcquire(keyId, bytes);
    }
}
