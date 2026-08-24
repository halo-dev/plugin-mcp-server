package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttachmentUploadLimiterTest {

    @Test
    void enforcesGlobalAndPerKeyByteBudgets() {
        var limiter = new AttachmentUploadLimiter(10, 6, 4, 3, 4);
        var first = limiter.tryAcquire("key-one", "policy-one", 6).orElseThrow();

        assertThat(limiter.tryAcquire("key-one", "policy-two", 1)).isEmpty();
        var second = limiter.tryAcquire("key-two", "policy-two", 4).orElseThrow();
        assertThat(limiter.tryAcquire("key-three", "policy-three", 1)).isEmpty();

        first.close();
        var replacement = limiter.tryAcquire("key-three", "policy-three", 6).orElseThrow();

        second.close();
        replacement.close();
    }

    @Test
    void enforcesUploadAndPolicyLimitsAndReleasesIdempotently() {
        var limiter = new AttachmentUploadLimiter(100, 100, 2, 1, 1);
        var first = limiter.tryAcquire("key-one", "policy-one", 1).orElseThrow();

        assertThat(limiter.tryAcquire("key-one", "policy-two", 1)).isEmpty();
        assertThat(limiter.tryAcquire("key-two", "policy-one", 1)).isEmpty();
        var second = limiter.tryAcquire("key-two", "policy-two", 1).orElseThrow();
        assertThat(limiter.tryAcquire("key-three", "policy-three", 1)).isEmpty();

        first.close();
        first.close();
        var replacement = limiter.tryAcquire("key-three", "policy-one", 1).orElseThrow();

        second.close();
        replacement.close();
    }
}
