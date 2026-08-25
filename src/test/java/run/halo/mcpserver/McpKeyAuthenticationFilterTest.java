package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class McpKeyAuthenticationFilterTest {

    @Mock
    McpAccessKeyService accessKeyService;

    @Mock
    HaloMcpServer mcpServer;

    McpKeyAuthenticationFilter filter;
    McpRequestRateLimiter rateLimiter;
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
        filter = new McpKeyAuthenticationFilter(
                accessKeyService, rateLimiter, new McpInFlightLimiter(), mcpServer);
    }

    @Test
    void springCreatesTheFilterUsingTheProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(McpAccessKeyService.class, () -> accessKeyService);
            context.registerBean(McpRequestRateLimiter.class, () -> rateLimiter);
            context.registerBean(McpInFlightLimiter.class, McpInFlightLimiter::new);
            context.registerBean(HaloMcpServer.class, () -> mcpServer);
            context.register(McpKeyAuthenticationFilter.class);

            context.refresh();

            assertThat(context.getBean(McpKeyAuthenticationFilter.class)).isNotNull();
        }
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
        when(accessKeyService.authenticate(rawToken, null)).thenReturn(Mono.empty());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
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
        when(accessKeyService.authenticate(rawToken, null)).thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                .block();

        verify(accessKeyService).authenticate(rawToken, null);
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
        when(accessKeyService.authenticate(rawToken, null)).thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION, "2099-01-01"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rateLimitsRequestsBeforeAccessKeyLookup() {
        for (var i = 0; i < McpRequestRateLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(rateLimiter.allowRequest(null)).isTrue();
        }
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("60");
        verify(accessKeyService, never()).authenticate(rawToken, null);
    }

    @Test
    void timesOutAStalledMcpRequestAndReleasesThePermit() {
        org.springframework.web.reactive.function.server.HandlerFunction<ServerResponse> stalled =
                request -> Mono.never();
        when(mcpServer.routerFunction()).thenReturn(
                RouterFunctions.route().POST("/mcp", stalled).build());
        var inFlightLimiter = new McpInFlightLimiter();
        var fastFilter = new McpKeyAuthenticationFilter(
                accessKeyService, new McpRequestRateLimiter(), inFlightLimiter, mcpServer,
                java.time.Duration.ofMillis(50));
        var keyId = "00000000-0000-0000-0000-000000000000";
        var rawToken = "hmcp_" + keyId + "_secret";
        when(accessKeyService.authenticate(rawToken, null))
                .thenReturn(Mono.just(new McpKeyAuthenticationToken(
                        keyId, "Automation", "hmcp_00000000", "admin", Set.of())));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        fastFilter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            assertThat(inFlightLimiter.tryAcquire(keyId)).isNotNull();
        }
    }

    @Test
    void timesOutAStalledAuthentication() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        when(accessKeyService.authenticate(rawToken, null)).thenReturn(Mono.never());
        var fastFilter = new McpKeyAuthenticationFilter(
                accessKeyService, new McpRequestRateLimiter(), new McpInFlightLimiter(), mcpServer,
                java.time.Duration.ofMillis(50));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        fastFilter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void limitsConcurrentAuthenticationBeforeCredentialLookup() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        when(accessKeyService.authenticate(
                org.mockito.ArgumentMatchers.eq(rawToken),
                org.mockito.ArgumentMatchers.any(InetSocketAddress.class)))
                .thenReturn(Mono.never());
        var inFlightLimiter = new McpInFlightLimiter();
        var boundedFilter = new McpKeyAuthenticationFilter(
                accessKeyService, new McpRequestRateLimiter(), inFlightLimiter, mcpServer);
        var requests = new ArrayList<reactor.core.Disposable>();
        try {
            for (var i = 0; i < McpInFlightLimiter.AUTHENTICATION_LIMIT; i++) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest
                        .post(McpKeyAuthenticationFilter.MCP_PATH)
                        .remoteAddress(new InetSocketAddress("192.0.2." + (i + 1), 8080))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
                requests.add(boundedFilter.filter(exchange,
                                ignored -> Mono.error(new AssertionError("Request must not continue")))
                        .subscribe());
            }
            var overflow = MockServerWebExchange.from(MockServerHttpRequest
                    .post(McpKeyAuthenticationFilter.MCP_PATH)
                    .remoteAddress(new InetSocketAddress("198.51.100.1", 8080))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

            StepVerifier.create(boundedFilter.filter(overflow,
                            ignored -> Mono.error(new AssertionError("Request must not continue"))))
                    .expectComplete()
                    .verify(Duration.ofMillis(200));

            assertThat(overflow.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        } finally {
            requests.forEach(reactor.core.Disposable::dispose);
        }
        var released = new ArrayList<KeyedInFlightLimiter.Lease>();
        for (var i = 0; i < McpInFlightLimiter.AUTHENTICATION_LIMIT; i++) {
            released.add(inFlightLimiter.tryAcquireAuthentication());
        }
        assertThat(released).doesNotContainNull();
        released.forEach(KeyedInFlightLimiter.Lease::close);
    }

    @Test
    void rejectsRequestsWhenTheInFlightBudgetIsExhausted() {
        var inFlightLimiter = new McpInFlightLimiter();
        var keyId = "00000000-0000-0000-0000-000000000000";
        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            assertThat(inFlightLimiter.tryAcquire(keyId)).isNotNull();
        }
        var rawToken = "hmcp_" + keyId + "_secret";
        when(accessKeyService.authenticate(rawToken, null))
                .thenReturn(Mono.just(new McpKeyAuthenticationToken(
                        keyId, "Automation", "hmcp_00000000", "admin", Set.of())));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        new McpKeyAuthenticationFilter(accessKeyService, rateLimiter, inFlightLimiter, mcpServer)
                .filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(handledPath.get()).isNull();
    }

    @Test
    void releasesThePermitWhenTheHandlerAssemblyFailsSynchronously() {
        org.springframework.web.reactive.function.server.RouterFunction<ServerResponse> broken =
                request -> {
                    throw new IllegalStateException("boom");
                };
        when(mcpServer.routerFunction()).thenReturn(broken);
        var inFlightLimiter = new McpInFlightLimiter();
        var brokenFilter = new McpKeyAuthenticationFilter(
                accessKeyService, new McpRequestRateLimiter(), inFlightLimiter, mcpServer);
        var keyId = "00000000-0000-0000-0000-000000000000";
        var rawToken = "hmcp_" + keyId + "_secret";
        when(accessKeyService.authenticate(rawToken, null))
                .thenReturn(Mono.just(new McpKeyAuthenticationToken(
                        keyId, "Automation", "hmcp_00000000", "admin", Set.of())));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        assertThatThrownBy(() -> brokenFilter
                        .filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                        .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        for (var i = 0; i < McpInFlightLimiter.PER_KEY_LIMIT; i++) {
            assertThat(inFlightLimiter.tryAcquire(keyId)).isNotNull();
        }
    }
}
