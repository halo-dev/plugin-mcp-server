package run.halo.mcpserver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
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
        key.getSpec().setAllowedIpRanges(Set.of("203.0.113.0/24"));
        when(accessKeyService.list()).thenReturn(Flux.just(key));

        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .get()
                .uri("/keys")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].allowedIpRanges[0]")
                .isEqualTo("203.0.113.0/24")
                .jsonPath("$[0].deletionTimestamp")
                .isEqualTo(deletionTimestamp.toString());
    }

    @Test
    void mapsInvalidIpRangesToBadRequestWhenUpdating() {
        when(toolCatalog.availableNames()).thenReturn(reactor.core.publisher.Mono.just(Set.of()));
        when(accessKeyService.allowedTools("test-key"))
                .thenReturn(reactor.core.publisher.Mono.just(Set.of()));
        when(accessKeyService.update(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(reactor.core.publisher.Mono.error(
                        new IllegalArgumentException("Invalid IP address or CIDR: invalid")));

        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .put()
                .uri("/keys/test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "displayName": "Test key",
                          "allowedTools": [],
                          "allowedIpRanges": ["invalid"],
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void preservesExistingUnavailableToolsButRejectsNewUnknownTools() {
        when(toolCatalog.availableNames())
                .thenReturn(reactor.core.publisher.Mono.just(Set.of("halo_get_post")));
        when(accessKeyService.allowedTools("test-key"))
                .thenReturn(reactor.core.publisher.Mono.just(Set.of("unavailable__tool")));
        var updated = new McpAccessKey();
        updated.setMetadata(new Metadata());
        updated.getMetadata().setName("test-key");
        updated.getSpec().setAllowedTools(Set.of("halo_get_post", "unavailable__tool"));
        when(accessKeyService.update(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(reactor.core.publisher.Mono.just(updated));

        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
        client.put()
                .uri("/keys/test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "displayName": "Test key",
                          "allowedTools": ["halo_get_post", "unavailable__tool"],
                          "allowedIpRanges": [],
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk();
        verify(accessKeyService).update(
                "test-key",
                "Test key",
                Set.of("halo_get_post", "unavailable__tool"),
                Set.of(),
                null,
                true);

        client.put()
                .uri("/keys/test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "displayName": "Test key",
                          "allowedTools": ["new-unknown__tool"],
                          "allowedIpRanges": [],
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void acceptsTheAllToolsWildcard() {
        when(toolCatalog.availableNames()).thenReturn(reactor.core.publisher.Mono.just(Set.of()));
        when(accessKeyService.allowedTools("test-key"))
                .thenReturn(reactor.core.publisher.Mono.just(Set.of()));
        var updated = new McpAccessKey();
        updated.setMetadata(new Metadata());
        updated.getMetadata().setName("test-key");
        updated.getSpec().setAllowedTools(Set.of("*"));
        when(accessKeyService.update(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(reactor.core.publisher.Mono.just(updated));

        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .build()
                .put()
                .uri("/keys/test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "displayName": "Test key",
                          "allowedTools": ["*"],
                          "allowedIpRanges": [],
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.allowedTools[0]")
                .isEqualTo("*");

        verify(accessKeyService).update(
                "test-key", "Test key", Set.of("*"), Set.of(), null, true);
    }

    @Test
    void createsAKeyWithTheAllToolsWildcard() {
        when(toolCatalog.availableNames()).thenReturn(reactor.core.publisher.Mono.just(Set.of()));
        var key = new McpAccessKey();
        key.setMetadata(new Metadata());
        key.getMetadata().setName("test-key");
        key.getSpec().setDisplayName("Automation");
        key.getSpec().setKeyPrefix("hmcp_test");
        key.getSpec().setOwnerName("admin");
        key.getSpec().setAllowedTools(Set.of("*"));
        when(accessKeyService.create("Automation", "admin", Set.of("*"), Set.of(), null))
                .thenReturn(reactor.core.publisher.Mono.just(
                        new McpAccessKeyService.CreatedKey(key, "secret")));

        var authentication = new UsernamePasswordAuthenticationToken("admin", "n/a");
        WebTestClient.bindToRouterFunction(endpoint.endpoint())
                .webFilter((exchange, chain) -> chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .build()
                .post()
                .uri("/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "displayName": "Automation",
                          "allowedTools": ["*"],
                          "allowedIpRanges": []
                        }
                        """)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.key.allowedTools[0]")
                .isEqualTo("*")
                .jsonPath("$.token")
                .isEqualTo("secret");

        verify(accessKeyService).create("Automation", "admin", Set.of("*"), Set.of(), null);
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
