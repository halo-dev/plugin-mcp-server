package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class McpAccessKeyServiceTest {

    @Mock
    ReactiveExtensionClient client;

    McpAccessKeyService service;

    @BeforeEach
    void setUp() {
        service = new McpAccessKeyService(client);
        when(client.create(any(McpAccessKey.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(client.update(any(McpAccessKey.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void createsAHashedKeyAndAuthenticatesIt() {
        var created = service.create(
                        "Automation",
                        "admin",
                        Set.of("halo_search_content"),
                        Set.of(),
                        Instant.now().plusSeconds(3600))
                .block();

        assertThat(created).isNotNull();
        assertThat(created.token()).startsWith("hmcp_");
        assertThat(created.accessKey().getSpec().getKeyHash())
                .isNotBlank()
                .doesNotContain(created.token());

        when(client.fetch(McpAccessKey.class, created.accessKey().getMetadata().getName()))
                .thenReturn(Mono.just(created.accessKey()));

        var authentication = service.authenticate(created.token(), null).block();

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("admin");
        assertThat(authentication.keyDisplayName()).isEqualTo("Automation");
        assertThat(authentication.keyPrefix()).startsWith("hmcp_");
        assertThat(authentication.allows("halo_search_content")).isTrue();
        verify(client).update(created.accessKey());
        assertThat(created.accessKey().getStatus().getLastUsedAt()).isNotNull();
    }

    @Test
    void rejectsDisabledAndExpiredKeys() {
        var created = service.create(
                        "Expired", "admin", Set.of(), Set.of(), Instant.now().minusSeconds(1))
                .block();
        when(client.fetch(McpAccessKey.class, created.accessKey().getMetadata().getName()))
                .thenReturn(Mono.just(created.accessKey()));

        assertThat(service.authenticate(created.token(), null).block()).isNull();

        created.accessKey().getSpec().setExpiresAt(null);
        created.accessKey().getSpec().setEnabled(false);
        assertThat(service.authenticate(created.token(), null).block()).isNull();
    }

    @Test
    void rejectsKeysBeingDeleted() {
        var created = service.create("Deleting", "admin", Set.of(), Set.of(), null)
                .block();
        created.accessKey().getMetadata().setDeletionTimestamp(Instant.now());
        when(client.fetch(McpAccessKey.class, created.accessKey().getMetadata().getName()))
                .thenReturn(Mono.just(created.accessKey()));

        assertThat(service.authenticate(created.token(), null).block()).isNull();
    }

    @Test
    void rotationInvalidatesThePreviousSecret() {
        var created = service.create("Automation", "admin", Set.of(), Set.of(), null)
                .block();
        var id = created.accessKey().getMetadata().getName();
        when(client.fetch(McpAccessKey.class, id)).thenReturn(Mono.just(created.accessKey()));

        var rotated = service.rotate(id).block();

        assertThat(rotated.token()).isNotEqualTo(created.token());
        assertThat(service.authenticate(created.token(), null).block()).isNull();
        assertThat(service.authenticate(rotated.token(), null).block()).isNotNull();
    }

    @Test
    void authenticatesOnlyFromAnAllowedIpRange() {
        var created = service.create(
                        "Restricted",
                        "admin",
                        Set.of(),
                        Set.of(" 203.0.113.0/24 "),
                        null)
                .block();
        var id = created.accessKey().getMetadata().getName();
        when(client.fetch(McpAccessKey.class, id)).thenReturn(Mono.just(created.accessKey()));

        assertThat(created.accessKey().getSpec().getAllowedIpRanges())
                .containsExactly("203.0.113.0/24");
        assertThat(service.authenticate(
                                created.token(), new InetSocketAddress("198.51.100.10", 443))
                        .block())
                .isNull();
        assertThat(created.accessKey().getStatus().getLastUsedAt()).isNull();

        assertThat(service.authenticate(
                                created.token(), new InetSocketAddress("203.0.113.42", 443))
                        .block())
                .isNotNull();
        assertThat(created.accessKey().getStatus().getLastUsedAt()).isNotNull();
    }

    @Test
    void authenticatesWhenTheKeyIsUnchangedDuringVerification() {
        var created = service.create(
                        "Automation", "admin", Set.of("halo_search_content"), Set.of(), null)
                .block();
        var id = created.accessKey().getMetadata().getName();
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.just(copyOf(created.accessKey())));

        assertThat(service.authenticate(created.token(), null).block()).isNotNull();
    }

    @Test
    void rejectsAuthenticationWhenRotationCommitsDuringVerification() {
        var created = service.create(
                        "Automation", "admin", Set.of("halo_search_content"), Set.of(), null)
                .block();
        var id = created.accessKey().getMetadata().getName();
        var rotated = copyOf(created.accessKey());
        rotated.getSpec().setKeyHash("rotated-hash");
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.just(rotated));

        assertThat(service.authenticate(created.token(), null).block()).isNull();
    }

    @Test
    void rejectsAuthenticationWhenDisablementOrScopeChangeCommitsDuringVerification() {
        var created = service.create(
                        "Automation", "admin", Set.of("halo_search_content"), Set.of(), null)
                .block();
        var id = created.accessKey().getMetadata().getName();

        var disabled = copyOf(created.accessKey());
        disabled.getSpec().setEnabled(false);
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.just(disabled));
        assertThat(service.authenticate(created.token(), null).block()).isNull();

        var narrowed = copyOf(created.accessKey());
        narrowed.getSpec().setAllowedTools(Set.of());
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.just(narrowed));
        assertThat(service.authenticate(created.token(), null).block()).isNull();
    }

    @Test
    void rejectsAuthenticationWhenDeletionCommitsDuringVerification() {
        var created = service.create("Automation", "admin", Set.of(), Set.of(), null).block();
        var id = created.accessKey().getMetadata().getName();

        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.empty());
        assertThat(service.authenticate(created.token(), null).block()).isNull();

        var deleting = copyOf(created.accessKey());
        deleting.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(created.accessKey()))
                .thenReturn(Mono.just(deleting));
        assertThat(service.authenticate(created.token(), null).block()).isNull();
    }

    private static McpAccessKey copyOf(McpAccessKey accessKey) {
        var copy = new McpAccessKey();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName(accessKey.getMetadata().getName());
        metadata.setVersion(accessKey.getMetadata().getVersion());
        copy.setMetadata(metadata);
        var source = accessKey.getSpec();
        var spec = new McpAccessKey.Spec();
        spec.setDisplayName(source.getDisplayName());
        spec.setKeyHash(source.getKeyHash());
        spec.setKeyPrefix(source.getKeyPrefix());
        spec.setOwnerName(source.getOwnerName());
        spec.setEnabled(source.isEnabled());
        spec.setExpiresAt(source.getExpiresAt());
        spec.setAllowedTools(new java.util.LinkedHashSet<>(source.getAllowedTools()));
        spec.setAllowedIpRanges(new java.util.LinkedHashSet<>(source.getAllowedIpRanges()));
        copy.setSpec(spec);
        copy.setStatus(accessKey.getStatus());
        return copy;
    }
}
