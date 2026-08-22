package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class AuthorizedMcpTransportTest {

    @Test
    void sanitizesInternalErrorsFromDefaultRequests() {
        var fixture = fixture();
        var request = new McpSchema.JSONRPCRequest("ping", 1, Map.of());

        var response = fixture.handler().handleRequest(McpTransportContext.EMPTY, request).block();

        assertInternalErrorIsSanitized(response, 1);
    }

    @Test
    void sanitizesInternalErrorsFromBuiltInToolCalls() {
        var fixture = fixture();
        var request = new McpSchema.JSONRPCRequest(
                "tools/call",
                2,
                Map.of("name", "halo_get_post", "arguments", Map.of()));
        var authentication = new McpKeyAuthenticationToken(
                "key-id", "Automation", "hmcp_key", "admin", Set.of("halo_get_post"));

        var response = fixture.handler()
                .handleRequest(McpTransportContext.EMPTY, request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertInternalErrorIsSanitized(response, 2);
    }

    private static Fixture fixture() {
        var delegate = mock(WebFluxStatelessServerTransport.class);
        var catalog = mock(McpToolCatalog.class);
        var registry = mock(McpToolRegistry.class);
        var authorization = new McpAuthorization();
        var transport = new AuthorizedMcpTransport(
                delegate,
                new JacksonMcpJsonMapper(JsonMapper.shared()),
                catalog,
                registry,
                authorization,
                new McpRequestRateLimiter(),
                new McpRecentCallHistory());
        var sdkHandler = mock(McpStatelessServerHandler.class);
        when(sdkHandler.handleRequest(any(), any())).thenReturn(Mono.just(
                McpSchema.JSONRPCResponse.error(
                        1,
                        new McpSchema.JSONRPCResponse.JSONRPCError(
                                -32603, "database password must stay private"))));

        transport.setMcpHandler(sdkHandler);
        var captor = ArgumentCaptor.forClass(McpStatelessServerHandler.class);
        verify(delegate).setMcpHandler(captor.capture());
        return new Fixture(captor.getValue());
    }

    private static void assertInternalErrorIsSanitized(
            McpSchema.JSONRPCResponse response, int requestId) {
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(requestId);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(response.error().message()).isEqualTo("Internal error");
        assertThat(response.toString()).doesNotContain("database password");
    }

    private record Fixture(McpStatelessServerHandler handler) {}
}
