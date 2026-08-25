package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AttachmentUploadLimiterTest {

    private static final long EIGHT_MIB = 8L * 1024 * 1024;

    @Test
    void enforcesThePerKeyBudgetIndependently() {
        var limiter = new AttachmentUploadLimiter();
        var reservations = new ArrayList<AttachmentUploadLimiter.Reservation>();

        for (var i = 0; i < AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES / EIGHT_MIB; i++) {
            reservations.add(limiter.tryAcquire("key-one", EIGHT_MIB));
        }
        assertThat(limiter.tryAcquire("key-one", EIGHT_MIB)).isNull();
        assertThat(limiter.tryAcquire("key-two", EIGHT_MIB)).isNotNull();

        reservations.getFirst().close();
        assertThat(limiter.tryAcquire("key-one", EIGHT_MIB)).isNotNull();
    }

    @Test
    void enforcesTheGlobalBudgetAcrossKeys() {
        var limiter = new AttachmentUploadLimiter();

        assertThat(limiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
        assertThat(limiter.tryAcquire("key-two", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
        assertThat(limiter.tryAcquire("key-three", EIGHT_MIB)).isNull();
    }

    @Test
    void rejectsASingleReservationLargerThanTheGlobalBudget() {
        var limiter = new AttachmentUploadLimiter();

        assertThat(limiter.tryAcquire("key-one", AttachmentUploadLimiter.GLOBAL_BUDGET_BYTES + 1))
                .isNull();
        assertThat(limiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
    }

    @Test
    void forgetsKeysWhoseReservationsHaveAllBeenReleased() {
        var limiter = new AttachmentUploadLimiter();

        for (var i = 0; i < AttachmentUploadLimiter.MAX_TRACKED_KEYS + 1; i++) {
            var reservation = limiter.tryAcquire("key-" + i, 1);
            assertThat(reservation).isNotNull();
            reservation.close();
        }

        assertThat(limiter.tryAcquire("key-fresh", EIGHT_MIB)).isNotNull();
    }

    @Test
    void keepsPerKeyAccountingConsistentUnderConcurrentChurn() throws InterruptedException {
        var limiter = new AttachmentUploadLimiter();
        // The test counters bracket acquire and close so they always overestimate the true
        // reserved bytes; exceeding the budget here therefore proves a real breach.
        var inFlightPerKey = new ConcurrentHashMap<String, AtomicLong>();
        var maxPerKey = new ConcurrentHashMap<String, AtomicLong>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        for (var t = 0; t < 8; t++) {
            var thread = new Thread(() -> {
                try {
                    for (var i = 0; i < 2_000; i++) {
                        var key = "key-" + i % 4;
                        var reservation = limiter.tryAcquire(key, EIGHT_MIB);
                        if (reservation == null) {
                            continue;
                        }
                        var usage = inFlightPerKey
                                .computeIfAbsent(key, ignored -> new AtomicLong());
                        var inFlight = usage.addAndGet(EIGHT_MIB);
                        maxPerKey.computeIfAbsent(key, ignored -> new AtomicLong())
                                .accumulateAndGet(inFlight, Math::max);
                        usage.addAndGet(-EIGHT_MIB);
                        reservation.close();
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
                        .isLessThanOrEqualTo(AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES));
        // Both budgets must drain back to zero once every reservation is closed.
        for (var i = 0; i < 4; i++) {
            var drained = limiter.tryAcquire("key-" + i, AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES);
            assertThat(drained).isNotNull();
            drained.close();
        }
    }

    @Test
    void ignoresDoubleRelease() {
        var limiter = new AttachmentUploadLimiter();
        var reservation = limiter.tryAcquire("key-one", EIGHT_MIB);
        assertThat(reservation).isNotNull();

        reservation.close();
        reservation.close();

        assertThat(limiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
    }
}
