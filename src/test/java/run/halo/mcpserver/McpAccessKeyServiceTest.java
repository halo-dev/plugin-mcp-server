package run.halo.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
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
    void rejectsAuthenticationWhenToolScopesChangeAfterInitialFetch() {
        var created = service.create(
                        "Automation",
                        "admin",
                        Set.of("halo_delete_post"),
                        Set.of(),
                        null)
                .block();
        var initial = created.accessKey();
        initial.getMetadata().setVersion(1L);
        var restricted = copyOf(initial);
        restricted.getMetadata().setVersion(2L);
        restricted.getSpec().setAllowedTools(Set.of("halo_search_content"));
        var stored = new AtomicReference<McpAccessKey>(initial);
        var mutationCommitted = new AtomicBoolean();
        when(client.fetch(McpAccessKey.class, initial.getMetadata().getName()))
                .thenAnswer(ignored -> Mono.defer(() -> Mono.justOrEmpty(stored.get()))
                        .doOnNext(snapshot -> {
                            if (mutationCommitted.compareAndSet(false, true)) {
                                stored.set(restricted);
                            }
                        }));

        var authentication = service.authenticate(created.token(), null).block();

        assertThat(authentication).isNull();
        assertThat(mutationCommitted).isTrue();
    }

    @Test
    void rejectsAuthenticationWhenOtherSecurityStateChangesAfterInitialFetch() {
        assertMutationRejected("rotated hash", key -> key.getSpec().setKeyHash("rotated-hash"));
        assertMutationRejected("disabled key", key -> key.getSpec().setEnabled(false));
        assertMutationRejected(
                "expired key", key -> key.getSpec().setExpiresAt(Instant.now().minusSeconds(1)));
        assertMutationRejected(
                "restricted IP",
                key -> key.getSpec().setAllowedIpRanges(Set.of("198.51.100.0/24")));
        assertMutationRejected("changed owner", key -> key.getSpec().setOwnerName("other-user"));
        assertStoredChangeRejected("deleted key", null);
    }

    @Test
    void authenticatesWhenOnlyStatusAndResourceVersionChangeAfterInitialFetch() {
        var created = newCreatedKey();
        var initial = created.accessKey();
        initial.getMetadata().setVersion(1L);
        var current = copyOf(initial);
        current.getMetadata().setVersion(2L);
        current.getStatus().setLastUsedAt(Instant.now().minusSeconds(30));
        stubMutationAfterInitialFetch(initial, current);

        var authentication = service.authenticate(
                        created.token(), new InetSocketAddress("203.0.113.42", 443))
                .block();

        assertThat(authentication).isNotNull();
        assertThat(authentication.allows("halo_delete_post")).isTrue();
    }

    @Test
    void revalidatesWhenLastUsedWriteConflictsWithARestriction() {
        var created = newCreatedKey();
        var initial = created.accessKey();
        initial.getMetadata().setVersion(1L);
        var restricted = copyOf(initial);
        restricted.getMetadata().setVersion(2L);
        restricted.getSpec().setAllowedTools(Set.of("halo_search_content"));
        var id = initial.getMetadata().getName();
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(initial), Mono.just(restricted));
        when(client.update(initial))
                .thenReturn(Mono.error(new OptimisticLockingFailureException("version changed")));

        var authentication = service.authenticate(created.token(), null).block();

        assertThat(authentication).isNull();
        verify(client, times(2)).fetch(McpAccessKey.class, id);
    }

    @Test
    void keepsLastUsedWritesBestEffortWhenThereIsNoVersionConflict() {
        var created = newCreatedKey();
        var initial = created.accessKey();
        var validated = copyOf(initial);
        var id = initial.getMetadata().getName();
        when(client.fetch(McpAccessKey.class, id))
                .thenReturn(Mono.just(initial), Mono.just(validated));
        when(client.update(initial)).thenReturn(Mono.error(new IllegalStateException("unavailable")));

        var authentication = service.authenticate(created.token(), null).block();

        assertThat(authentication).isNotNull();
        assertThat(authentication.allows("halo_delete_post")).isTrue();
    }

    private void assertMutationRejected(
            String description, java.util.function.Consumer<McpAccessKey> mutation) {
        var created = newCreatedKey();
        var initial = created.accessKey();
        initial.getMetadata().setVersion(1L);
        var changed = copyOf(initial);
        changed.getMetadata().setVersion(2L);
        mutation.accept(changed);
        stubMutationAfterInitialFetch(initial, changed);

        var authentication = service.authenticate(
                        created.token(), new InetSocketAddress("203.0.113.42", 443))
                .block();

        assertThat(authentication).as(description).isNull();
    }

    private void assertStoredChangeRejected(String description, McpAccessKey changed) {
        var created = newCreatedKey();
        var initial = created.accessKey();
        initial.getMetadata().setVersion(1L);
        stubMutationAfterInitialFetch(initial, changed);

        var authentication = service.authenticate(
                        created.token(), new InetSocketAddress("203.0.113.42", 443))
                .block();

        assertThat(authentication).as(description).isNull();
    }

    private McpAccessKeyService.CreatedKey newCreatedKey() {
        return service.create(
                        "Automation",
                        "admin",
                        Set.of("halo_delete_post"),
                        Set.of(),
                        null)
                .block();
    }

    private void stubMutationAfterInitialFetch(McpAccessKey initial, McpAccessKey changed) {
        var stored = new AtomicReference<>(initial);
        var mutationCommitted = new AtomicBoolean();
        when(client.fetch(McpAccessKey.class, initial.getMetadata().getName()))
                .thenAnswer(ignored -> Mono.defer(() -> Mono.justOrEmpty(stored.get()))
                        .doOnNext(snapshot -> {
                            if (mutationCommitted.compareAndSet(false, true)) {
                                stored.set(changed);
                            }
                        }));
    }

    private static McpAccessKey copyOf(McpAccessKey source) {
        var copy = new McpAccessKey();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName(source.getMetadata().getName());
        metadata.setVersion(source.getMetadata().getVersion());
        metadata.setDeletionTimestamp(source.getMetadata().getDeletionTimestamp());
        copy.setMetadata(metadata);
        var spec = new McpAccessKey.Spec();
        spec.setDisplayName(source.getSpec().getDisplayName());
        spec.setKeyHash(source.getSpec().getKeyHash());
        spec.setKeyPrefix(source.getSpec().getKeyPrefix());
        spec.setOwnerName(source.getSpec().getOwnerName());
        spec.setEnabled(source.getSpec().isEnabled());
        spec.setExpiresAt(source.getSpec().getExpiresAt());
        spec.setAllowedTools(Set.copyOf(source.getSpec().getAllowedTools()));
        spec.setAllowedIpRanges(Set.copyOf(source.getSpec().getAllowedIpRanges()));
        copy.setSpec(spec);
        var status = new McpAccessKey.Status();
        status.setLastUsedAt(source.getStatus() == null ? null : source.getStatus().getLastUsedAt());
        copy.setStatus(status);
        return copy;
    }
}
