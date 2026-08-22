package run.halo.mcpserver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import run.halo.app.extension.Metadata;

@ExtendWith(MockitoExtension.class)
class McpAccessKeyEndpointTest {

    @Mock
    McpAccessKeyService accessKeyService;

    @Mock
    McpToolCatalog toolCatalog;

    @Mock
    McpRecentCallHistory recentCallHistory;

    @InjectMocks
    McpAccessKeyEndpoint endpoint;

    @Test
    void listsDeletionTimestamp() {
        var deletionTimestamp = Instant.parse("2026-08-21T08:00:00Z");
        var key = new McpAccessKey();
        var metadata = new Metadata();
        metadata.setName("test-key");
        metadata.setDeletionTimestamp(deletionTimestamp);
        key.setMetadata(metadata);
        key.getSpec().setDisplayName("Test key");
        key.getSpec().setKeyPrefix("hmcp_test");
        key.getSpec().setOwnerName("admin");
        when(accessKeyService.list()).thenReturn(Flux.just(key));

        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .get()
                .uri("/keys")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].deletionTimestamp")
                .isEqualTo(deletionTimestamp.toString());
    }

    @Test
    void listsRecentCallsWithFilters() {
        var call = new McpRecentCall(
                1,
                Instant.parse("2026-08-21T08:00:00Z"),
                12,
                "key-1",
                "Automation",
                "hmcp_key",
                "admin",
                "halo_get_post",
                McpToolSourceType.BUILT_IN,
                "plugin-mcp-server",
                McpCallOutcome.SUCCESS,
                null);
        when(recentCallHistory.list(any())).thenReturn(
                new McpRecentCallPage(java.util.List.of(call), 2, 10, 11, 2, false));

        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .get()
                .uri("/recent-calls?page=2&size=10&keyId=key-1&toolName=halo_get_post&outcome=SUCCESS")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].toolName")
                .isEqualTo("halo_get_post")
                .jsonPath("$.page")
                .isEqualTo(2)
                .jsonPath("$.total")
                .isEqualTo(11);
    }

    @Test
    void rejectsInvalidRecentCallPagination() {
        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .get()
                .uri("/recent-calls?size=101")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
