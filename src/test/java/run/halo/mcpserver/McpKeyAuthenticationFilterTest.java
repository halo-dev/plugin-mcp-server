package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class McpKeyAuthenticationFilterTest {

    private static final InetSocketAddress REMOTE_ADDRESS =
            new InetSocketAddress("203.0.113.8", 41321);

    @Mock
    McpAccessKeyService accessKeyService;

    @Mock
    HaloMcpServer mcpServer;

    McpKeyAuthenticationFilter filter;
    McpRequestRateLimiter rateLimiter;
    McpRequestConcurrencyLimiter concurrencyLimiter;
    AtomicReference<String> handledPath;
    AtomicReference<String> handledAuthorization;
    AtomicReference<Object> currentAuthentication;

    @BeforeEach
    void setUp() {
        handledPath = new AtomicReference<>();
        handledAuthorization = new AtomicReference<>();
        currentAuthentication = new AtomicReference<>();
        org.springframework.web.reactive.function.server.HandlerFunction<ServerResponse> handler = request -> {
            handledPath.set(request.path());
            handledAuthorization.set(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
            return ReactiveSecurityContextHolder.getContext()
                    .doOnNext(context -> currentAuthentication.set(context.getAuthentication()))
                    .then(ServerResponse.noContent().build());
        };
        when(mcpServer.routerFunction()).thenReturn(RouterFunctions.route()
                .POST("/mcp", handler)
                .POST("/mcp/", handler)
                .build());
        when(mcpServer.protocolVersions()).thenReturn(java.util.List.of("2025-11-25"));
        rateLimiter = new McpRequestRateLimiter();
        concurrencyLimiter = new McpRequestConcurrencyLimiter();
        filter = new McpKeyAuthenticationFilter(
                accessKeyService, rateLimiter, concurrencyLimiter, mcpServer);
    }

    @Test
    void authenticatesAndStripsTheMcpBearerTokenBeforeTheHaloJwtFilter() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "00000000-0000-0000-0000-000000000000",
                "Automation",
                "hmcp_00000000",
                "admin",
                Set.of("halo_search_content"));
        var remoteAddress = new InetSocketAddress("203.0.113.8", 41321);
        when(accessKeyService.authenticate(rawToken, remoteAddress))
                .thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(remoteAddress)
                .header(HttpHeaders.AUTHORIZATION, "bEaReR " + rawToken)
                .header("X-Forwarded-For", "198.51.100.9"));
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                .block();

        assertThat(handledAuthorization.get()).isNull();
        assertThat(handledPath.get()).isEqualTo(McpKeyAuthenticationFilter.MCP_PATH);
        assertThat(currentAuthentication.get()).isSameAs(authentication);
        verify(accessKeyService).authenticate(rawToken, remoteAddress);
    }

    @Test
    void rejectsAnInvalidMcpKey() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_invalid";
        when(accessKeyService.authenticate(rawToken, REMOTE_ADDRESS)).thenReturn(Mono.empty());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"MCP\"");
    }

    @Test
    void rejectsAMissingMcpKey() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"MCP\"");
    }

    @Test
    void neverUsesAnMcpKeyOutsideTheMcpEndpoint() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/apis/api.console.halo.run/v1alpha1/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
        var forwarded = new AtomicReference<ServerWebExchange>();

        filter.filter(exchange, next -> {
                    forwarded.set(next);
                    return Mono.empty();
                })
                .block();

        verify(accessKeyService, never()).authenticate(rawToken, null);
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + rawToken);
    }

    @Test
    void authenticatesATrailingSlashAsTheSameMcpEndpoint() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "00000000-0000-0000-0000-000000000000",
                "Automation",
                "hmcp_00000000",
                "admin",
                Set.of());
        when(accessKeyService.authenticate(rawToken, REMOTE_ADDRESS))
                .thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp/")
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                .block();

        verify(accessKeyService).authenticate(rawToken, REMOTE_ADDRESS);
        assertThat(handledPath.get()).isEqualTo("/mcp/");
    }

    @Test
    void rejectsAnUnsupportedProtocolVersionAfterAuthentication() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "00000000-0000-0000-0000-000000000000",
                "Automation",
                "hmcp_00000000",
                "admin",
                Set.of("halo_search_content"));
        when(accessKeyService.authenticate(rawToken, REMOTE_ADDRESS))
                .thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION, "2099-01-01"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rateLimitsRequestsBeforeAccessKeyLookup() {
        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(rateLimiter.allowRequest(REMOTE_ADDRESS)).isTrue();
        }
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("60");
        verify(accessKeyService, never()).authenticate(rawToken, REMOTE_ADDRESS);
    }

    @Test
    void timesOutAndCancelsAnIncompleteBodyAndReleasesItsPermit() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "key-id", "Automation", "hmcp_00000000", "admin", Set.of());
        when(accessKeyService.authenticate(rawToken, REMOTE_ADDRESS))
                .thenReturn(Mono.just(authentication));
        var bodyCancelled = new AtomicBoolean();
        org.springframework.web.reactive.function.server.HandlerFunction<ServerResponse> handler =
                request -> request.bodyToMono(String.class)
                        .then(ServerResponse.noContent().build());
        when(mcpServer.routerFunction()).thenReturn(RouterFunctions.route()
                .POST("/mcp", handler)
                .build());
        concurrencyLimiter = new McpRequestConcurrencyLimiter(1, 1);
        filter = new McpKeyAuthenticationFilter(
                accessKeyService, rateLimiter, concurrencyLimiter, mcpServer);
        var stalledExchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                        McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .body(Flux.<DataBuffer>never()
                        .doOnCancel(() -> bodyCancelled.set(true))));

        StepVerifier.withVirtualTime(() -> filter.filter(
                        stalledExchange,
                        ignored -> Mono.error(new AssertionError("Halo chain must not continue"))))
                .thenAwait(McpKeyAuthenticationFilter.REQUEST_TIMEOUT.plusMillis(1))
                .verifyComplete();

        assertThat(stalledExchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(bodyCancelled).isTrue();

        var completedExchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                        McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .body("complete"));
        filter.filter(
                        completedExchange,
                        ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                .block();

        assertThat(completedExchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void rejectsPerKeyConcurrencyBeforeInvokingTheHandler() {
        assertCapacityRejection(new McpRequestConcurrencyLimiter(2, 1), "key-id");
    }

    @Test
    void rejectsGlobalConcurrencyBeforeInvokingTheHandler() {
        assertCapacityRejection(new McpRequestConcurrencyLimiter(1, 1), "other-key-id");
    }

    private void assertCapacityRejection(
            McpRequestConcurrencyLimiter limiter, String occupiedKeyId) {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "key-id", "Automation", "hmcp_00000000", "admin", Set.of());
        when(accessKeyService.authenticate(rawToken, REMOTE_ADDRESS))
                .thenReturn(Mono.just(authentication));
        concurrencyLimiter = limiter;
        filter = new McpKeyAuthenticationFilter(
                accessKeyService, rateLimiter, concurrencyLimiter, mcpServer);
        var occupied = concurrencyLimiter.tryAcquire(occupiedKeyId).orElseThrow();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                        McpKeyAuthenticationFilter.MCP_PATH)
                .remoteAddress(REMOTE_ADDRESS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
        try {
            filter.filter(
                            exchange,
                            ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                    .block();
        } finally {
            occupied.close();
        }

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("1");
        assertThat(handledPath).hasNullValue();
    }
}
