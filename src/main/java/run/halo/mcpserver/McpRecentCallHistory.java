package run.halo.mcpserver;

import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
class McpRecentCallHistory {

    static final int CAPACITY = 500;
    private static final Pattern TOOL_NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_.:/-]{1,128}");
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("(?:[A-Z][A-Z0-9_]{0,63}|-?[0-9]{1,10})");

    private final Object monitor = new Object();
    private final Deque<McpRecentCall> calls = new ArrayDeque<>(CAPACITY);
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;
    private final LongSupplier nanoTime;

    McpRecentCallHistory() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    McpRecentCallHistory(Clock clock, LongSupplier nanoTime) {
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    Mono<McpSchema.JSONRPCResponse> observe(
            McpKeyAuthenticationToken authentication,
            String toolName,
            Supplier<Mono<McpSchema.JSONRPCResponse>> action) {
        return Mono.defer(() -> {
            var startedAt = clock.instant();
            var startedNanos = nanoTime.getAsLong();
            var recorded = new AtomicBoolean();
            return Mono.defer(action)
                    .doOnSuccess(response -> recordOnce(
                            recorded,
                            authentication,
                            toolName,
                            startedAt,
                            startedNanos,
                            response == null
                                    ? new CallResult(McpCallOutcome.INTERNAL_ERROR, "INTERNAL")
                                    : result(response)))
                    .doOnError(error -> recordOnce(
                            recorded,
                            authentication,
                            toolName,
                            startedAt,
                            startedNanos,
                            new CallResult(McpCallOutcome.INTERNAL_ERROR, "INTERNAL")))
                    .doOnCancel(() -> recordOnce(
                            recorded,
                            authentication,
                            toolName,
                            startedAt,
                            startedNanos,
                            new CallResult(McpCallOutcome.CANCELLED, null)));
        });
    }

    McpRecentCallPage list(McpRecentCallQuery query) {
        var snapshot = snapshot();
        var matching = snapshot.stream()
                .filter(call -> !StringUtils.hasText(query.keyId()) || query.keyId().equals(call.keyId()))
                .filter(call -> !StringUtils.hasText(query.toolName()) || query.toolName().equals(call.toolName()))
                .filter(call -> query.outcome() == null || query.outcome() == call.outcome())
                .toList();
        var total = matching.size();
        var from = Math.min((long) (query.page() - 1) * query.size(), total);
        var to = Math.min(from + query.size(), total);
        var totalPages = total == 0 ? 0 : (total + query.size() - 1) / query.size();
        return new McpRecentCallPage(
                matching.subList((int) from, (int) to),
                query.page(),
                query.size(),
                total,
                totalPages,
                query.page() < totalPages);
    }

    private java.util.List<McpRecentCall> snapshot() {
        synchronized (monitor) {
            return new ArrayList<>(calls);
        }
    }

    private void recordOnce(
            AtomicBoolean recorded,
            McpKeyAuthenticationToken authentication,
            String toolName,
            Instant startedAt,
            long startedNanos,
            CallResult result) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            var normalizedToolName = normalizedToolName(authentication, toolName);
            append(new McpRecentCall(
                    sequence.incrementAndGet(),
                    startedAt,
                    elapsedMillis(startedNanos),
                    authentication.keyId(),
                    authentication.keyDisplayName(),
                    authentication.keyPrefix(),
                    authentication.getName(),
                    normalizedToolName,
                    sourceType(normalizedToolName),
                    sourcePlugin(normalizedToolName),
                    result.outcome(),
                    result.errorCode()));
        } catch (RuntimeException ignored) {
            // Recent calls are best-effort and must never change a tool response.
        }
    }

    private void append(McpRecentCall call) {
        synchronized (monitor) {
            calls.addFirst(call);
            while (calls.size() > CAPACITY) {
                calls.removeLast();
            }
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (nanoTime.getAsLong() - startedNanos) / 1_000_000);
    }

    private static CallResult result(McpSchema.JSONRPCResponse response) {
        if (response.error() != null) {
            var outcome = Integer.valueOf(-32603).equals(response.error().code())
                    ? McpCallOutcome.INTERNAL_ERROR
                    : McpCallOutcome.PROTOCOL_ERROR;
            return new CallResult(outcome, String.valueOf(response.error().code()));
        }
        if (response.result() instanceof McpSchema.CallToolResult toolResult
                && Boolean.TRUE.equals(toolResult.isError())) {
            return new CallResult(McpCallOutcome.TOOL_ERROR, errorCode(toolResult.structuredContent()));
        }
        return new CallResult(McpCallOutcome.SUCCESS, null);
    }

    private static String errorCode(Object structuredContent) {
        if (!(structuredContent instanceof Map<?, ?> content)
                || !(content.get("error") instanceof Map<?, ?> error)) {
            return null;
        }
        var code = error.get("code");
        return code instanceof String || code instanceof Number
                ? normalizedErrorCode(String.valueOf(code))
                : null;
    }

    private static String normalizedToolName(
            McpKeyAuthenticationToken authentication, String toolName) {
        return toolName != null
                        && authentication.allows(toolName)
                        && TOOL_NAME_PATTERN.matcher(toolName).matches()
                ? toolName
                : "<invalid>";
    }

    private static String normalizedErrorCode(String errorCode) {
        return errorCode != null && ERROR_CODE_PATTERN.matcher(errorCode).matches()
                ? errorCode
                : null;
    }

    private static McpToolSourceType sourceType(String toolName) {
        if ("<invalid>".equals(toolName)) {
            return McpToolSourceType.UNKNOWN;
        }
        return toolName.contains("/") ? McpToolSourceType.PLUGIN : McpToolSourceType.BUILT_IN;
    }

    private static String sourcePlugin(String toolName) {
        if ("<invalid>".equals(toolName)) {
            return null;
        }
        var separator = toolName.indexOf('/');
        return separator > 0 ? toolName.substring(0, separator) : "plugin-mcp-server";
    }

    private record CallResult(McpCallOutcome outcome, String errorCode) {}
}

record McpRecentCallQuery(int page, int size, String keyId, String toolName, McpCallOutcome outcome) {}

@Schema(name = "McpRecentCall")
record McpRecentCall(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long durationMillis,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String keyId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String keyDisplayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String keyPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ownerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String toolName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpToolSourceType sourceType,
        String sourcePlugin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpCallOutcome outcome,
        String errorCode) {}

@Schema(name = "McpRecentCallPage")
record McpRecentCallPage(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.util.List<McpRecentCall> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int total,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalPages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext) {}

enum McpCallOutcome {
    SUCCESS,
    TOOL_ERROR,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
    CANCELLED
}

enum McpToolSourceType {
    BUILT_IN,
    PLUGIN,
    UNKNOWN
}
