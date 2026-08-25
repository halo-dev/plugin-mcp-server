package run.halo.mcpserver.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Process-local in-flight byte budgets that bound concurrent attachment uploads. */
@Component
class AttachmentUploadLimiter {

    static final long GLOBAL_BUDGET_BYTES = 64L * 1024 * 1024;
    static final long PER_KEY_BUDGET_BYTES = 32L * 1024 * 1024;
    static final int MAX_TRACKED_KEYS = 10_000;

    private final AtomicLong globalBytes = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> keyBytes = new ConcurrentHashMap<>();

    /**
     * Reserves decoded bytes against the global and per-key budgets, or returns null when either
     * is exhausted. The caller must close the reservation exactly once when the upload terminates.
     */
    Reservation tryAcquire(String keyId, long bytes) {
        if (bytes > GLOBAL_BUDGET_BYTES) {
            return null;
        }
        if (keyBytes.size() >= MAX_TRACKED_KEYS && !keyBytes.containsKey(keyId)) {
            return null;
        }
        if (!reserve(globalBytes, GLOBAL_BUDGET_BYTES, bytes)) {
            return null;
        }
        // Create-or-add and the budget check run inside the key's map bin so a reservation can
        // never land on a counter that a concurrent release has already unmapped.
        var acquired = new AtomicReference<AtomicLong>();
        keyBytes.compute(keyId, (key, existing) -> {
            var usage = existing == null ? new AtomicLong() : existing;
            if (usage.addAndGet(bytes) > PER_KEY_BUDGET_BYTES) {
                usage.addAndGet(-bytes);
                return existing;
            }
            acquired.set(usage);
            return usage;
        });
        var usage = acquired.get();
        if (usage == null) {
            globalBytes.addAndGet(-bytes);
            return null;
        }
        return new Reservation(keyId, usage, bytes);
    }

    private static boolean reserve(AtomicLong usage, long limit, long bytes) {
        while (true) {
            var current = usage.get();
            if (current + bytes > limit) {
                return false;
            }
            if (usage.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    final class Reservation implements AutoCloseable {

        private final String keyId;
        private final AtomicLong usage;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(String keyId, AtomicLong usage, long bytes) {
            this.keyId = keyId;
            this.usage = usage;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                globalBytes.addAndGet(-bytes);
                if (usage.addAndGet(-bytes) <= 0) {
                    // Drop the entry once the key holds no reservation so churn through many keys
                    // cannot permanently exhaust MAX_TRACKED_KEYS. The identity and zero
                    // rechecks keep a racing acquisition from losing its counter.
                    keyBytes.computeIfPresent(keyId,
                            (key, mapped) -> mapped == usage && mapped.get() <= 0 ? null : mapped);
                }
            }
        }
    }
}
