package run.halo.mcpserver;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Process-local fixed-window limits that protect MCP requests and tool invocation. */
@Component
class McpRequestRateLimiter {

    static final int REQUESTS_PER_MINUTE = 600;
    static final int TOOL_CALLS_PER_MINUTE = 120;
    static final long RETRY_AFTER_SECONDS = 60;
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    private final AtomicReference<Window> window;
    private final LongSupplier currentTimeMillis;

    McpRequestRateLimiter() {
        this(System::currentTimeMillis);
    }

    McpRequestRateLimiter(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
        this.window = new AtomicReference<>(new Window(currentTimeMillis.getAsLong()));
    }

    boolean allowRequest(InetSocketAddress remoteAddress) {
        try {
            var source = McpIpAllowlist.resolve(remoteAddress).getHostAddress();
            return allow(currentWindow().requestCounts, source, REQUESTS_PER_MINUTE);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    boolean allowTool(String keyId, String toolName) {
        return allow(currentWindow().toolCounts, keyId + '\n' + toolName, TOOL_CALLS_PER_MINUTE);
    }

    void clear() {
        window.set(new Window(currentTimeMillis.getAsLong()));
    }

    private boolean allow(
            ConcurrentHashMap<String, AtomicInteger> counts, String key, int limit) {
        if (counts.size() >= MAX_TRACKED_KEYS && !counts.containsKey(key)) {
            return false;
        }
        return counts.computeIfAbsent(key, ignored -> new AtomicInteger())
                        .incrementAndGet()
                <= limit;
    }

    private Window currentWindow() {
        var now = currentTimeMillis.getAsLong();
        while (true) {
            var current = window.get();
            if (now - current.startedAt < WINDOW_MILLIS) {
                return current;
            }
            var replacement = new Window(now);
            if (window.compareAndSet(current, replacement)) {
                return replacement;
            }
        }
    }

    private static final class Window {

        private final long startedAt;
        private final ConcurrentHashMap<String, AtomicInteger> requestCounts =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> toolCounts =
                new ConcurrentHashMap<>();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
