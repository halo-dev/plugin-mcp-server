package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
