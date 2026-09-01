package run.halo.mcpserver;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
class McpAccessKeyService {

    private static final String TOKEN_PREFIX = "hmcp_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(McpAccessKeyService.class);
    private static final java.time.Duration LAST_USED_WRITE_INTERVAL = java.time.Duration.ofMinutes(5);

    private final ReactiveExtensionClient client;
    private final PasswordEncoder passwordEncoder;
    private final ConcurrentHashMap<String, Instant> lastUsedWrites = new ConcurrentHashMap<>();

    McpAccessKeyService(ReactiveExtensionClient client) {
        this.client = client;
        this.passwordEncoder = new BCryptPasswordEncoder(10);
    }

    Flux<McpAccessKey> list() {
        return client.listAll(
                McpAccessKey.class,
                new ListOptions(),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")));
    }

    Mono<CreatedKey> create(
            String displayName,
            String ownerName,
            Set<String> allowedTools,
            Set<String> allowedIpRanges,
            Instant expiresAt) {
        var normalizedIpRanges = McpIpAllowlist.normalize(allowedIpRanges);
        var id = UUID.randomUUID().toString();
        var secret = randomSecret();
        var token = token(id, secret);
        return encode(secret).flatMap(hash -> {
            var accessKey = new McpAccessKey();
            var metadata = new Metadata();
            metadata.setName(id);
            accessKey.setMetadata(metadata);
            var spec = new McpAccessKey.Spec();
            spec.setDisplayName(requireDisplayName(displayName));
            spec.setKeyHash(hash);
            spec.setKeyPrefix(TOKEN_PREFIX + id.substring(0, 8));
            spec.setOwnerName(ownerName);
            spec.setEnabled(true);
            spec.setExpiresAt(expiresAt);
            spec.setAllowedTools(copyTools(allowedTools));
            spec.setAllowedIpRanges(normalizedIpRanges);
            accessKey.setSpec(spec);
            return client.create(accessKey).map(created -> new CreatedKey(created, token));
        });
    }

    Mono<McpAccessKey> update(
            String id,
            String displayName,
            Set<String> allowedTools,
            Set<String> allowedIpRanges,
            Instant expiresAt,
            boolean enabled) {
        var normalizedIpRanges = McpIpAllowlist.normalize(allowedIpRanges);
        return get(id).flatMap(accessKey -> {
            var spec = accessKey.getSpec();
            spec.setDisplayName(requireDisplayName(displayName));
            spec.setAllowedTools(copyTools(allowedTools));
            spec.setAllowedIpRanges(normalizedIpRanges);
            spec.setExpiresAt(expiresAt);
            spec.setEnabled(enabled);
            return client.update(accessKey);
        });
    }

    Mono<Set<String>> allowedTools(String id) {
        return get(id).map(accessKey -> copyTools(accessKey.getSpec().getAllowedTools()));
    }

    Mono<CreatedKey> rotate(String id) {
        var secret = randomSecret();
        var token = token(id, secret);
        return get(id).flatMap(accessKey -> encode(secret).flatMap(hash -> {
            accessKey.getSpec().setKeyHash(hash);
            if (accessKey.getStatus() == null) {
                accessKey.setStatus(new McpAccessKey.Status());
            }
            accessKey.getStatus().setLastUsedAt(null);
            lastUsedWrites.remove(id);
            return client.update(accessKey).map(updated -> new CreatedKey(updated, token));
        }));
    }

    Mono<Void> delete(String id) {
        return get(id).flatMap(client::delete).then();
    }

    Mono<McpKeyAuthenticationToken> authenticate(
            String rawToken, InetSocketAddress remoteAddress) {
        var parsed = parse(rawToken);
        if (parsed == null) {
            return Mono.empty();
        }
        return client.fetch(McpAccessKey.class, parsed.id())
                .filter(this::active)
                .flatMap(accessKey -> matches(parsed.secret(), accessKey.getSpec().getKeyHash())
                        .filter(Boolean::booleanValue)
                        .filter(ignored -> McpIpAllowlist.allows(
                                accessKey.getSpec().getAllowedIpRanges(), remoteAddress))
                        .flatMap(ignored -> revalidate(accessKey))
                        .flatMap(fresh -> touch(fresh).thenReturn(new McpKeyAuthenticationToken(
                                parsed.id(),
                                fresh.getSpec().getDisplayName(),
                                fresh.getSpec().getKeyPrefix(),
                                fresh.getSpec().getOwnerName(),
                                fresh.getSpec().getAllowedTools() == null
                                        ? Set.of()
                                        : fresh.getSpec().getAllowedTools()))));
    }

    /**
     * Re-fetches the key after asynchronous password verification so a rotation, disablement,
     * scope change, or deletion committed meanwhile invalidates this authentication. Status-only
     * writes such as last-used updates do not affect the comparison. This relies on the extension
     * client returning a freshly deserialized instance per fetch; a shared mutable instance would
     * make the spec comparison trivially equal and silently defeat this check.
     */
    private Mono<McpAccessKey> revalidate(McpAccessKey snapshot) {
        return client.fetch(McpAccessKey.class, snapshot.getMetadata().getName())
                .filter(this::active)
                .filter(fresh -> fresh.getSpec().equals(snapshot.getSpec()));
    }

    private Mono<McpAccessKey> get(String id) {
        return client.fetch(McpAccessKey.class, id)
                .switchIfEmpty(Mono.error(new AccessKeyNotFoundException(id)));
    }

    private boolean active(McpAccessKey accessKey) {
        var spec = accessKey.getSpec();
        return !ExtensionUtil.isDeleted(accessKey)
                && spec != null
                && spec.isEnabled()
                && (spec.getExpiresAt() == null || spec.getExpiresAt().isAfter(Instant.now()));
    }

    private Mono<Void> touch(McpAccessKey accessKey) {
        var id = accessKey.getMetadata().getName();
        var now = Instant.now();
        var previous = lastUsedWrites.putIfAbsent(id, now);
        if (previous != null && previous.plus(LAST_USED_WRITE_INTERVAL).isAfter(now)) {
            return Mono.empty();
        }
        lastUsedWrites.put(id, now);
        if (accessKey.getStatus() == null) {
            accessKey.setStatus(new McpAccessKey.Status());
        }
        accessKey.getStatus().setLastUsedAt(now);
        return client.update(accessKey)
                .then()
                .onErrorResume(error -> {
                    log.debug("Failed to update last-used time for MCP key {}", id, error);
                    return Mono.empty();
                });
    }

    private Mono<String> encode(String secret) {
        return Mono.fromCallable(() -> passwordEncoder.encode(secret))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> matches(String secret, String hash) {
        if (!StringUtils.hasText(hash)) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> passwordEncoder.matches(secret, hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String randomSecret() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String token(String id, String secret) {
        return TOKEN_PREFIX + id + "_" + secret;
    }

    private static ParsedKey parse(String token) {
        if (!StringUtils.hasText(token) || !token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        var separator = token.indexOf('_', TOKEN_PREFIX.length());
        if (separator < 0 || separator == token.length() - 1) {
            return null;
        }
        var id = token.substring(TOKEN_PREFIX.length(), separator);
        var secret = token.substring(separator + 1);
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException error) {
            return null;
        }
        return new ParsedKey(id, secret);
    }

    private static String requireDisplayName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        var normalized = displayName.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("displayName must not exceed 100 characters");
        }
        return normalized;
    }

    private static Set<String> copyTools(Set<String> allowedTools) {
        if (allowedTools == null) {
            return new LinkedHashSet<>();
        }
        return allowedTools.stream()
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    record CreatedKey(McpAccessKey accessKey, String token) {}

    private record ParsedKey(String id, String secret) {}

    static final class AccessKeyNotFoundException extends RuntimeException {
        AccessKeyNotFoundException(String id) {
            super("MCP access key not found: " + id);
        }
    }
}
