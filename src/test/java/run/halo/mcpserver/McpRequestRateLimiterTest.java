package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class McpRequestRateLimiterTest {

    @Test
    void limitsRequestsByRemoteAddressAndResetsAfterAWindow() {
        var now = new AtomicLong();
        var limiter = new McpRequestRateLimiter(now::get);
        var remote = new InetSocketAddress("127.0.0.1", 8090);

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(remote)).isTrue();
        }
        assertThat(limiter.allowRequest(remote)).isFalse();

        now.addAndGet(60_000);
        assertThat(limiter.allowRequest(remote)).isTrue();
    }

    @Test
    void isolatesToolLimitsByKeyAndToolAndCanBeCleared() {
        var limiter = new McpRequestRateLimiter(() -> 0L);

        for (var i = 0; i < McpRequestRateLimiter.TOOL_CALLS_PER_MINUTE; i++) {
            assertThat(limiter.allowTool("key-one", "demo/one")).isTrue();
        }
        assertThat(limiter.allowTool("key-one", "demo/one")).isFalse();
        assertThat(limiter.allowTool("key-one", "demo/two")).isTrue();
        assertThat(limiter.allowTool("key-two", "demo/one")).isTrue();

        limiter.clear();
        assertThat(limiter.allowTool("key-one", "demo/one")).isTrue();
    }
}
