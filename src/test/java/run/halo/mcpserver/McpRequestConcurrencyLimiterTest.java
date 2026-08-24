package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpRequestConcurrencyLimiterTest {

    @Test
    void enforcesPerKeyAndGlobalLimitsAndReleasesPermits() {
        var limiter = new McpRequestConcurrencyLimiter(2, 1);
        var first = limiter.tryAcquire("key-one").orElseThrow();

        assertThat(limiter.tryAcquire("key-one")).isEmpty();

        var second = limiter.tryAcquire("key-two").orElseThrow();
        assertThat(limiter.tryAcquire("key-three")).isEmpty();

        first.close();
        first.close();
        var replacement = limiter.tryAcquire("key-three").orElseThrow();

        second.close();
        replacement.close();
        var finalPermit = limiter.tryAcquire("key-one").orElseThrow();
        finalPermit.close();
    }
}
