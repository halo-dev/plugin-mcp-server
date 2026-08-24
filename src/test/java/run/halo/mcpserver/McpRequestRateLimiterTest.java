package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

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
    void isolatesNumericAddressesFromForwardedHeaders() {
        var limiter = new McpRequestRateLimiter(() -> 0L);
        var firstClient = transformedRemoteAddress("X-Forwarded-For", "203.0.113.42");
        var secondClient = transformedRemoteAddress(
                "Forwarded", "for=\"[2001:db8::9]\"");
        assertThat(firstClient.isUnresolved()).isTrue();
        assertThat(secondClient.isUnresolved()).isTrue();

        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.allowRequest(firstClient)).isTrue();
        }
        assertThat(limiter.allowRequest(firstClient)).isFalse();
        assertThat(limiter.allowRequest(secondClient)).isTrue();
    }

    @Test
    void acceptsForwardedAddressesWithPorts() {
        var limiter = new McpRequestRateLimiter(() -> 0L);

        assertThat(limiter.allowRequest(transformedRemoteAddress(
                        "X-Forwarded-For", "203.0.113.42:4567")))
                .isTrue();
        assertThat(limiter.allowRequest(transformedRemoteAddress(
                        "Forwarded", "for=\"[2001:db8::42]:4567\"")))
                .isTrue();
    }

    @Test
    void rejectsUnavailableAndNonnumericAddresses() {
        var limiter = new McpRequestRateLimiter(() -> 0L);

        assertThat(limiter.allowRequest(null)).isFalse();
        assertThat(limiter.allowRequest(
                        InetSocketAddress.createUnresolved("client.example.com", 443)))
                .isFalse();
        assertThat(limiter.allowRequest(transformedRemoteAddress(
                        "X-Forwarded-For", "203.0.113.42:not-a-port")))
                .isFalse();
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

    private static InetSocketAddress transformedRemoteAddress(String header, String value) {
        var request = MockServerHttpRequest.get("http://localhost/mcp")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 443))
                .header(header, value)
                .build();
        return new ForwardedHeaderTransformer().apply(request).getRemoteAddress();
    }
}
