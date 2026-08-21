package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class McpKeyAuthenticationFilterTest {

    @Mock
    McpAccessKeyService accessKeyService;

    @Mock
    HaloMcpServer mcpServer;

    McpKeyAuthenticationFilter filter;
    AtomicReference<String> handledPath;
    AtomicReference<String> handledAuthorization;
    AtomicReference<Object> currentAuthentication;

    @BeforeEach
    void setUp() {
        handledPath = new AtomicReference<>();
        handledAuthorization = new AtomicReference<>();
        currentAuthentication = new AtomicReference<>();
        when(mcpServer.routerFunction()).thenReturn(RouterFunctions.route()
                .POST("/mcp", request -> {
                    handledPath.set(request.path());
                    handledAuthorization.set(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
                    return ReactiveSecurityContextHolder.getContext()
                            .doOnNext(context -> currentAuthentication.set(context.getAuthentication()))
                            .then(ServerResponse.noContent().build());
                })
                .build());
        filter = new McpKeyAuthenticationFilter(accessKeyService, mcpServer);
    }

    @Test
    void authenticatesAndStripsTheMcpBearerTokenBeforeTheHaloJwtFilter() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_secret";
        var authentication = new McpKeyAuthenticationToken(
                "00000000-0000-0000-0000-000000000000",
                "admin",
                Set.of("halo_search_content"));
        when(accessKeyService.authenticate(rawToken)).thenReturn(Mono.just(authentication));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Halo chain must not continue")))
                .block();

        assertThat(handledAuthorization.get()).isNull();
        assertThat(handledPath.get()).isEqualTo(McpKeyAuthenticationFilter.MCP_PATH);
        assertThat(currentAuthentication.get()).isSameAs(authentication);
    }

    @Test
    void rejectsAnInvalidMcpKey() {
        var rawToken = "hmcp_00000000-0000-0000-0000-000000000000_invalid";
        when(accessKeyService.authenticate(rawToken)).thenReturn(Mono.empty());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAMissingMcpKey() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(McpKeyAuthenticationFilter.MCP_PATH));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Request must not continue")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

        verify(accessKeyService, never()).authenticate(rawToken);
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + rawToken);
    }
}
