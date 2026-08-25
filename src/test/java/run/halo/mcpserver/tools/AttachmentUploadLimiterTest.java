package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import run.halo.mcpserver.KeyedInFlightLimiter;

class AttachmentUploadLimiterTest {

    private static final int EIGHT_MIB = 8 * 1024 * 1024;

    @Test
    void appliesUploadByteBudgets() {
        var limiter = new AttachmentUploadLimiter();
        var reservations = new ArrayList<KeyedInFlightLimiter.Lease>();

        for (var i = 0; i < AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES / EIGHT_MIB; i++) {
            reservations.add(limiter.tryAcquire("key-one", EIGHT_MIB));
        }
        assertThat(limiter.tryAcquire("key-one", EIGHT_MIB)).isNull();
        assertThat(limiter.tryAcquire("key-two", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
        assertThat(limiter.tryAcquire("key-three", EIGHT_MIB)).isNull();

        reservations.getFirst().close();
        assertThat(limiter.tryAcquire("key-one", EIGHT_MIB)).isNotNull();
    }

    @Test
    void rejectsReservationsLargerThanTheGlobalBudget() {
        var limiter = new AttachmentUploadLimiter();

        assertThat(limiter.tryAcquire("key-one", AttachmentUploadLimiter.GLOBAL_BUDGET_BYTES + 1))
                .isNull();
    }
}
