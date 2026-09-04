package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
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

    @Test
    void wildcardListsEveryCurrentlyAvailableTool() {
        var fixture = fixture();
        var first = McpSchema.Tool.builder("halo_first", Map.of("type", "object")).build();
        var addedLater = McpSchema.Tool.builder("PluginExample__added_later", Map.of("type", "object"))
                .build();
        when(fixture.catalog().protocolTools()).thenReturn(Mono.just(List.of(first, addedLater)));
        var request = new McpSchema.JSONRPCRequest("tools/list", 3, Map.of());
        var authentication = new McpKeyAuthenticationToken(
                "key-id", "Automation", "hmcp_key", "admin", Set.of("*"));

        var response = fixture.handler()
                .handleRequest(McpTransportContext.EMPTY, request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(response.result()).isInstanceOf(McpSchema.ListToolsResult.class);
        var result = (McpSchema.ListToolsResult) response.result();
        assertThat(result.tools()).extracting(McpSchema.Tool::name)
                .containsExactly("halo_first", "PluginExample__added_later");
    }

    @Test
    void wildcardCallsShareOneRateLimitBucket() {
        var fixture = fixture();
        var authentication = new McpKeyAuthenticationToken(
                "key-id", "Automation", "hmcp_key", "admin", Set.of("*"));

        for (var toolName : List.of("missing_one", "missing_two")) {
            var request = new McpSchema.JSONRPCRequest(
                    "tools/call", 4, Map.of("name", toolName, "arguments", Map.of()));
            fixture.handler()
                    .handleRequest(McpTransportContext.EMPTY, request)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                    .block();
        }

        verify(fixture.rateLimiter(), times(2)).allowTool("key-id", "*");
    }

    @Test
    void acceptsInitializedNotificationWithoutDelegatingToMissingSdkHandler() {
        var fixture = fixture();
        var notification = new McpSchema.JSONRPCNotification("notifications/initialized", Map.of());

        StepVerifier.create(fixture.handler()
                        .handleNotification(McpTransportContext.EMPTY, notification))
                .verifyComplete();

        verify(fixture.sdkHandler(), never()).handleNotification(any(), any());
    }

    @Test
    void delegatesOtherNotificationsToSdkHandler() {
        var fixture = fixture();
        var notification = new McpSchema.JSONRPCNotification("notifications/progress", Map.of());

        StepVerifier.create(fixture.handler()
                        .handleNotification(McpTransportContext.EMPTY, notification))
                .verifyComplete();

        verify(fixture.sdkHandler()).handleNotification(McpTransportContext.EMPTY, notification);
    }

    private static Fixture fixture() {
        var delegate = mock(WebFluxStatelessServerTransport.class);
        var catalog = mock(McpToolCatalog.class);
        var registry = mock(McpToolRegistry.class);
        var rateLimiter = mock(McpRequestRateLimiter.class);
        when(rateLimiter.allowTool(any(), any())).thenReturn(true);
        var authorization = new McpAuthorization();
        var transport = new AuthorizedMcpTransport(
                delegate,
                new JacksonMcpJsonMapper(JsonMapper.shared()),
                catalog,
                registry,
                authorization,
                rateLimiter,
                new McpRecentCallHistory());
        var sdkHandler = mock(McpStatelessServerHandler.class);
        when(sdkHandler.handleNotification(any(), any())).thenReturn(Mono.empty());
        when(sdkHandler.handleRequest(any(), any())).thenReturn(Mono.just(
                McpSchema.JSONRPCResponse.error(
                        1,
                        new McpSchema.JSONRPCResponse.JSONRPCError(
                                -32603, "database password must stay private"))));

        transport.setMcpHandler(sdkHandler);
        var captor = ArgumentCaptor.forClass(McpStatelessServerHandler.class);
        verify(delegate).setMcpHandler(captor.capture());
        return new Fixture(captor.getValue(), sdkHandler, catalog, rateLimiter);
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

    private record Fixture(
            McpStatelessServerHandler handler,
            McpStatelessServerHandler sdkHandler,
            McpToolCatalog catalog,
            McpRequestRateLimiter rateLimiter) {}
}
