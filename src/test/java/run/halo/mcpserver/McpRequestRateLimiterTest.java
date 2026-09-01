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
            assertThat(limiter.allowTool("key-one", "demo__one")).isTrue();
        }
        assertThat(limiter.allowTool("key-one", "demo__one")).isFalse();
        assertThat(limiter.allowTool("key-one", "demo__two")).isTrue();
        assertThat(limiter.allowTool("key-two", "demo__one")).isTrue();

        limiter.clear();
        assertThat(limiter.allowTool("key-one", "demo__one")).isTrue();
    }

    @Test
    void givesForwardedClientsIndependentBuckets() {
        var limiter = new McpRequestRateLimiter(() -> 0L);
        var first = InetSocketAddress.createUnresolved("203.0.113.10", 443);
        var second = InetSocketAddress.createUnresolved("203.0.113.11", 443);

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(first)).isTrue();
        }
        assertThat(limiter.allowRequest(first)).isFalse();
        assertThat(limiter.allowRequest(second)).isTrue();
    }

    @Test
    void canonicalizesResolvedAndUnresolvedFormsOfOneAddress() {
        var limiter = new McpRequestRateLimiter(() -> 0L);
        var resolved = new InetSocketAddress("203.0.113.10", 443);
        var unresolved = InetSocketAddress.createUnresolved("203.0.113.10", 443);

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(resolved)).isTrue();
        }
        assertThat(limiter.allowRequest(unresolved)).isFalse();
    }

    @Test
    void constrainsUnresolvableClientsToOneSharedBucket() {
        var limiter = new McpRequestRateLimiter(() -> 0L);

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(null)).isTrue();
        }
        assertThat(limiter.allowRequest(InetSocketAddress.createUnresolved("not-an-ip", 443)))
                .isFalse();
        assertThat(limiter.allowRequest(new InetSocketAddress("203.0.113.10", 443))).isTrue();
    }

    @Test
    void bucketsHostnamesAsUnknownWithoutResolvingThem() {
        var limiter = new McpRequestRateLimiter(() -> 0L);
        var disguised = InetSocketAddress.createUnresolved("1:a.attacker.example", 443);

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(disguised)).isTrue();
        }
        assertThat(limiter.allowRequest(null)).isFalse();
    }
}
