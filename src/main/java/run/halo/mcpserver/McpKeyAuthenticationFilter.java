package run.halo.mcpserver;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.BeforeSecurityWebFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class McpKeyAuthenticationFilter implements BeforeSecurityWebFilter {

    static final String MCP_PATH = "/mcp";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String BEARER_SCHEME = "Bearer ";
    private static final String CONCURRENCY_RETRY_AFTER_SECONDS = "1";

    private final McpAccessKeyService accessKeyService;
    private final McpRequestRateLimiter rateLimiter;
    private final McpRequestConcurrencyLimiter concurrencyLimiter;
    private final WebHandler mcpHandler;
    private final java.util.Set<String> protocolVersions;

    McpKeyAuthenticationFilter(
            McpAccessKeyService accessKeyService,
            McpRequestRateLimiter rateLimiter,
            McpRequestConcurrencyLimiter concurrencyLimiter,
            HaloMcpServer mcpServer) {
        this.accessKeyService = accessKeyService;
        this.rateLimiter = rateLimiter;
        this.concurrencyLimiter = concurrencyLimiter;
        this.mcpHandler = RouterFunctions.toWebHandler(mcpServer.routerFunction());
        this.protocolVersions = java.util.Set.copyOf(mcpServer.protocolVersions());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().value();
        var authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION);
        if (!isMcpPath(path)) {
            return chain.filter(exchange);
        }
        if (!hasMcpBearerToken(authorization)) {
            return unauthorized(exchange);
        }
        if (!rateLimiter.allowRequest(exchange.getRequest().getRemoteAddress())) {
            return tooManyRequests(exchange);
        }
        var rawToken = authorization.substring(BEARER_SCHEME.length());
        return accessKeyService.authenticate(rawToken, exchange.getRequest().getRemoteAddress())
                .flatMap(authentication -> {
                    if (!hasSupportedProtocolVersion(exchange)) {
                        return badRequest(exchange).thenReturn(true);
                    }
                    var permit = concurrencyLimiter.tryAcquire(authentication.keyId());
                    if (permit.isEmpty()) {
                        return atCapacity(exchange).thenReturn(true);
                    }
                    return Mono.using(
                                    permit::orElseThrow,
                                    ignored -> {
                                        var request = exchange.getRequest().mutate()
                                                .headers(headers -> headers.remove(AUTHORIZATION))
                                                .build();
                                        return mcpHandler.handle(
                                                        exchange.mutate().request(request).build())
                                                .contextWrite(org.springframework.security.core.context
                                                        .ReactiveSecurityContextHolder
                                                        .withAuthentication(authentication));
                                    },
                                    McpRequestConcurrencyLimiter.Permit::close)
                            .thenReturn(true);
                })
                .defaultIfEmpty(false)
                .flatMap(handled -> handled ? Mono.empty() : unauthorized(exchange))
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(TimeoutException.class, ignored -> requestTimedOut(exchange));
    }

    private static boolean isMcpPath(String path) {
        return MCP_PATH.equals(path) || (MCP_PATH + "/").equals(path);
    }

    private static boolean hasMcpBearerToken(String authorization) {
        return authorization != null
                && authorization.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())
                && authorization.regionMatches(
                        false, BEARER_SCHEME.length(), "hmcp_", 0, "hmcp_".length());
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(WWW_AUTHENTICATE, "Bearer realm=\"MCP\"");
        return exchange.getResponse().setComplete();
    }

    private boolean hasSupportedProtocolVersion(ServerWebExchange exchange) {
        var versions = exchange.getRequest().getHeaders()
                .get(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION);
        return versions == null
                || versions.isEmpty()
                || (versions.size() == 1 && protocolVersions.contains(versions.getFirst()));
    }

    private static Mono<Void> badRequest(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        return exchange.getResponse().setComplete();
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(
                RETRY_AFTER, String.valueOf(McpRequestRateLimiter.RETRY_AFTER_SECONDS));
        return exchange.getResponse().setComplete();
    }

    private static Mono<Void> atCapacity(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(
                RETRY_AFTER, CONCURRENCY_RETRY_AFTER_SECONDS);
        return exchange.getResponse().setComplete();
    }

    private static Mono<Void> requestTimedOut(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
        return exchange.getResponse().setComplete();
    }
}
