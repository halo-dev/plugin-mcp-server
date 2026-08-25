package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class McpInFlightLimiterTest {

    @Test
    void appliesRequestLimits() {
        var limiter = new McpInFlightLimiter();
        var permits = new ArrayList<KeyedInFlightLimiter.Lease>();

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            permits.add(limiter.tryAcquire("key-one"));
        }
        assertThat(limiter.tryAcquire("key-one")).isNull();
        var otherKey = limiter.tryAcquire("key-two");
        assertThat(otherKey).isNotNull();

        permits.forEach(KeyedInFlightLimiter.Lease::close);
        otherKey.close();
        for (var i = 0; i < McpInFlightLimiter.GLOBAL_LIMIT; i++) {
            assertThat(limiter.tryAcquire("key-" + i)).isNotNull();
        }
        assertThat(limiter.tryAcquire("key-extra")).isNull();
    }
}
