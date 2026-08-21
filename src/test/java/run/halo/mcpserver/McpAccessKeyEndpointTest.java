package run.halo.mcpserver;

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
}
