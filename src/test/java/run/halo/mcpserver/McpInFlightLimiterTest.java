package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class McpInFlightLimiterTest {

    @Test
    void enforcesThePerKeyLimitIndependently() {
        var limiter = new McpInFlightLimiter();
        var permits = new ArrayList<McpInFlightLimiter.Permit>();

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            permits.add(limiter.tryAcquire("key-one"));
        }
        assertThat(limiter.tryAcquire("key-one")).isNull();
        assertThat(limiter.tryAcquire("key-two")).isNotNull();

        permits.getFirst().close();
        assertThat(limiter.tryAcquire("key-one")).isNotNull();
    }

    @Test
    void enforcesTheGlobalLimitAcrossKeys() {
        var limiter = new McpInFlightLimiter();
        var permits = new ArrayList<McpInFlightLimiter.Permit>();

        var key = 0;
        while (permits.size() < McpInFlightLimiter.GLOBAL_LIMIT) {
            var permit = limiter.tryAcquire("key-" + key++);
            assertThat(permit).isNotNull();
            permits.add(permit);
        }
        assertThat(limiter.tryAcquire("key-extra")).isNull();

        permits.forEach(McpInFlightLimiter.Permit::close);
        for (var i = 0; i < McpInFlightLimiter.GLOBAL_LIMIT; i++) {
            assertThat(limiter.tryAcquire("key-reused-" + i)).isNotNull();
        }
    }

    @Test
    void ignoresDoubleRelease() {
        var limiter = new McpInFlightLimiter();
        var permit = limiter.tryAcquire("key-one");
        assertThat(permit).isNotNull();

        permit.close();
        permit.close();

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            assertThat(limiter.tryAcquire("key-one")).isNotNull();
        }
        assertThat(limiter.tryAcquire("key-one")).isNull();
    }
}
