package run.halo.mcpserver;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

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
    private static final String BEARER_PREFIX = "Bearer hmcp_";

    private final McpAccessKeyService accessKeyService;
    private final WebHandler mcpHandler;

    McpKeyAuthenticationFilter(McpAccessKeyService accessKeyService, HaloMcpServer mcpServer) {
        this.accessKeyService = accessKeyService;
        this.mcpHandler = RouterFunctions.toWebHandler(mcpServer.routerFunction());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().value();
        var authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION);
        if (!isMcpPath(path)) {
            return chain.filter(exchange);
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        var rawToken = authorization.substring("Bearer ".length());
        return accessKeyService.authenticate(rawToken)
                .flatMap(authentication -> {
                    var request = exchange.getRequest().mutate()
                            .headers(headers -> headers.remove(AUTHORIZATION))
                            .build();
                    return mcpHandler.handle(exchange.mutate().request(request).build())
                            .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                                    .withAuthentication(authentication));
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)));
    }

    private static boolean isMcpPath(String path) {
        return MCP_PATH.equals(path) || (MCP_PATH + "/").equals(path);
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
