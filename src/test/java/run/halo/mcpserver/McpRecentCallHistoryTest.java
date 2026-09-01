package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpRecentCallHistoryTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T08:00:00Z");

    AtomicLong nanoTime;
    McpRecentCallHistory history;
    McpKeyAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        nanoTime = new AtomicLong();
        history = new McpRecentCallHistory(
                Clock.fixed(STARTED_AT, ZoneOffset.UTC), nanoTime::get);
        authentication = new McpKeyAuthenticationToken(
                "key-1",
                "Automation",
                "hmcp_key",
                "admin",
                Set.of(
                        "halo_get_post",
                        "demo__private",
                        "demo__broken",
                        "demo__slow",
                        "demo__read"));
    }

    @Test
    void recordsSuccessWithoutArgumentsOrResults() {
        nanoTime.set(1_000_000);
        history.observe(authentication, "halo_get_post", () -> {
                    nanoTime.set(13_000_000);
                    return Mono.just(McpSchema.JSONRPCResponse.result(
                            1,
                            McpSchema.CallToolResult.builder()
                                    .structuredContent(Map.of("secret", "must-not-be-recorded"))
                                    .build()));
                })
                .block();

        var page = history.list(new McpRecentCallQuery(1, 20, null, null, null));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst())
                .extracting(
                        McpRecentCall::startedAt,
                        McpRecentCall::durationMillis,
                        McpRecentCall::keyDisplayName,
                        McpRecentCall::keyPrefix,
                        McpRecentCall::toolName,
                        McpRecentCall::sourceType,
                        McpRecentCall::sourcePlugin,
                        McpRecentCall::outcome,
                        McpRecentCall::errorCode)
                .containsExactly(
                        STARTED_AT,
                        12L,
                        "Automation",
                        "hmcp_key",
                        "halo_get_post",
                        McpToolSourceType.BUILT_IN,
                        "mcp-server",
                        McpCallOutcome.SUCCESS,
                        null);
        assertThat(page.items().getFirst().toString()).doesNotContain("must-not-be-recorded");
    }

    @Test
    void classifiesToolProtocolInternalAndCancelledResults() {
        var toolError = McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(Map.of("error", Map.of("code", "FORBIDDEN", "message", "secret")))
                .build();
        history.observe(
                        authentication,
                        "demo__private",
                        () -> Mono.just(McpSchema.JSONRPCResponse.result(1, toolError)))
                .block();
        history.observe(authentication, "", () -> Mono.just(McpSchema.JSONRPCResponse.error(
                        2, new McpSchema.JSONRPCResponse.JSONRPCError(-32602, "invalid"))))
                .block();
        history.observe(authentication, "demo__broken", () -> Mono.error(new IllegalStateException("secret")))
                .onErrorResume(error -> Mono.empty())
                .block();
        var cancelled = history.observe(authentication, "demo__slow", Mono::never).subscribe();
        cancelled.dispose();

        var calls = history.list(new McpRecentCallQuery(1, 20, null, null, null)).items();

        assertThat(calls).extracting(McpRecentCall::outcome).containsExactly(
                McpCallOutcome.CANCELLED,
                McpCallOutcome.INTERNAL_ERROR,
                McpCallOutcome.PROTOCOL_ERROR,
                McpCallOutcome.TOOL_ERROR);
        assertThat(calls.getLast().errorCode()).isEqualTo("FORBIDDEN");
        assertThat(calls.toString()).doesNotContain("secret");
    }

    @Test
    void sanitizesUntrustedToolNamesAndErrorCodes() {
        var toolError = McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(Map.of(
                        "error", Map.of("code", "secret data that must not be retained")))
                .build();
        history.observe(
                        authentication,
                        "SECRET_TOKEN_MUST_NOT_BE_RETAINED",
                        () -> Mono.just(McpSchema.JSONRPCResponse.result(1, toolError)))
                .block();
        history.observe(
                        authentication,
                        "a".repeat(129),
                        () -> Mono.just(McpSchema.JSONRPCResponse.result(2, Map.of())))
                .block();
        var numericToolError = McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(Map.of(
                        "error", Map.of("code", new java.math.BigInteger("9".repeat(11)))))
                .build();
        history.observe(
                        authentication,
                        "demo__private",
                        () -> Mono.just(McpSchema.JSONRPCResponse.result(3, numericToolError)))
                .block();

        var calls = history.list(new McpRecentCallQuery(1, 20, null, null, null)).items();

        assertThat(calls)
                .extracting(
                        McpRecentCall::toolName,
                        McpRecentCall::sourceType,
                        McpRecentCall::sourcePlugin,
                        McpRecentCall::errorCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "demo__private", McpToolSourceType.PLUGIN, "demo", null),
                        org.assertj.core.groups.Tuple.tuple(
                                "<invalid>", McpToolSourceType.UNKNOWN, null, null),
                        org.assertj.core.groups.Tuple.tuple(
                                "<invalid>", McpToolSourceType.UNKNOWN, null, null));
        assertThat(calls.toString())
                .doesNotContain("MUST_NOT_BE_RETAINED")
                .doesNotContain("must not be retained")
                .doesNotContain("9".repeat(11));
    }

    @Test
    void keepsOnlyTheNewestFiveHundredCallsAndFiltersBeforePaging() {
        for (var index = 0; index < McpRecentCallHistory.CAPACITY + 2; index++) {
            var toolName = index % 2 == 0 ? "halo_get_post" : "demo__read";
            history.observe(authentication, toolName, () -> Mono.just(McpSchema.JSONRPCResponse.result(
                            1, McpSchema.CallToolResult.builder().structuredContent(Map.of()).build())))
                    .block();
        }

        var all = history.list(new McpRecentCallQuery(1, McpRecentCallHistory.CAPACITY, null, null, null));
        var plugins = history.list(new McpRecentCallQuery(
                2, 10, null, "demo__read", McpCallOutcome.SUCCESS));

        assertThat(all.total()).isEqualTo(McpRecentCallHistory.CAPACITY);
        assertThat(all.items().getFirst().id()).isEqualTo(McpRecentCallHistory.CAPACITY + 2L);
        assertThat(all.items().getLast().id()).isEqualTo(3L);
        assertThat(plugins.total()).isEqualTo(250);
        assertThat(plugins.items())
                .hasSize(10)
                .allMatch(call -> call.toolName().equals("demo__read"));
        assertThat(plugins.page()).isEqualTo(2);
        assertThat(plugins.totalPages()).isEqualTo(25);
        assertThat(plugins.hasNext()).isTrue();
    }
}
