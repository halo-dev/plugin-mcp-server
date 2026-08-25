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
