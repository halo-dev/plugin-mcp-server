package run.halo.mcpserver;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Process-local fixed-window limits that protect MCP authentication and tool invocation. */
@Component
class McpRequestRateLimiter {

    static final int AUTHENTICATIONS_PER_MINUTE = 600;
    static final int TOOL_CALLS_PER_MINUTE = 120;
    static final long RETRY_AFTER_SECONDS = 60;
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    private final ConcurrentHashMap<String, AtomicInteger> authenticationCounts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> toolCounts = new ConcurrentHashMap<>();
    private final AtomicLong windowStartedAt;
    private final LongSupplier currentTimeMillis;

    McpRequestRateLimiter() {
        this(System::currentTimeMillis);
    }

    McpRequestRateLimiter(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
        this.windowStartedAt = new AtomicLong(currentTimeMillis.getAsLong());
    }

    boolean allowAuthentication(InetSocketAddress remoteAddress) {
        var source = remoteAddress == null || remoteAddress.getAddress() == null
                ? "unknown"
                : remoteAddress.getAddress().getHostAddress();
        return allow(authenticationCounts, source, AUTHENTICATIONS_PER_MINUTE);
    }

    boolean allowTool(String keyId, String toolName) {
        return allow(toolCounts, keyId + '\n' + toolName, TOOL_CALLS_PER_MINUTE);
    }

    void clear() {
        authenticationCounts.clear();
        toolCounts.clear();
        windowStartedAt.set(currentTimeMillis.getAsLong());
    }

    private boolean allow(
            ConcurrentHashMap<String, AtomicInteger> counts, String key, int limit) {
        resetExpiredWindow(currentTimeMillis.getAsLong());
        if (counts.size() >= MAX_TRACKED_KEYS && !counts.containsKey(key)) {
            return false;
        }
        return counts.computeIfAbsent(key, ignored -> new AtomicInteger())
                        .incrementAndGet()
                <= limit;
    }

    private void resetExpiredWindow(long now) {
        var startedAt = windowStartedAt.get();
        if (now - startedAt < WINDOW_MILLIS) {
            return;
        }
        synchronized (this) {
            startedAt = windowStartedAt.get();
            if (now - startedAt >= WINDOW_MILLIS) {
                authenticationCounts.clear();
                toolCounts.clear();
                windowStartedAt.set(now);
            }
        }
    }
}
