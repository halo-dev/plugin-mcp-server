package run.halo.mcpserver;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local global and per-key in-flight budgets. */
public final class KeyedInFlightLimiter {

    private final int perKeyLimit;
    private final int maxTrackedKeys;
    private final Semaphore globalPermits;
    private final ConcurrentHashMap<String, Semaphore> keyPermits = new ConcurrentHashMap<>();

    public KeyedInFlightLimiter(int globalLimit, int perKeyLimit, int maxTrackedKeys) {
        this.perKeyLimit = perKeyLimit;
        this.maxTrackedKeys = maxTrackedKeys;
        this.globalPermits = new Semaphore(globalLimit);
    }

    public Lease tryAcquire(String keyId, int permits) {
        if (permits < 0 || keyPermits.size() >= maxTrackedKeys && !keyPermits.containsKey(keyId)) {
            return null;
        }
        if (!globalPermits.tryAcquire(permits)) {
            return null;
        }
        var acquired = new AtomicReference<Semaphore>();
        keyPermits.compute(keyId, (key, existing) -> {
            var semaphore = existing == null ? new Semaphore(perKeyLimit) : existing;
            if (!semaphore.tryAcquire(permits)) {
                return existing;
            }
            acquired.set(semaphore);
            return semaphore;
        });
        var semaphore = acquired.get();
        if (semaphore == null) {
            globalPermits.release(permits);
            return null;
        }
        return new Lease(keyId, semaphore, permits);
    }

    public final class Lease implements AutoCloseable {

        private final String keyId;
        private final Semaphore semaphore;
        private final int permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String keyId, Semaphore semaphore, int permits) {
            this.keyId = keyId;
            this.semaphore = semaphore;
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                globalPermits.release(permits);
                keyPermits.compute(keyId, (key, mapped) -> {
                    semaphore.release(permits);
                    return mapped == semaphore && semaphore.availablePermits() == perKeyLimit
                            ? null
                            : mapped;
                });
            }
        }
    }
}
