package run.halo.mcpserver;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolException;

/** Tool authorization backed exclusively by the current MCP access key. */
@Component
public class McpAuthorization {

    Mono<Void> require(String toolName) {
        return authentication().flatMap(authentication -> authentication.allows(toolName)
                ? Mono.empty()
                : Mono.error(new McpToolException(
                        "FORBIDDEN", "The MCP access key cannot use " + toolName)));
    }

    public <T> Mono<T> authorize(String toolName, Supplier<Mono<T>> action) {
        return require(toolName).then(Mono.defer(() -> {
            try {
                var result = action.get();
                return result == null
                        ? Mono.error(new McpToolException("INTERNAL", "The tool returned no result"))
                        : result;
            } catch (Throwable error) {
                return Mono.error(error);
            }
        }));
    }

    /** Runs an action with the current key's ID while keeping the authentication token internal. */
    public <T> Mono<T> withKeyId(java.util.function.Function<String, Mono<T>> action) {
        return authentication().map(McpKeyAuthenticationToken::keyId).flatMap(keyId -> {
            try {
                var result = action.apply(keyId);
                return result == null
                        ? Mono.error(new McpToolException("INTERNAL", "The tool returned no result"))
                        : result;
            } catch (Throwable error) {
                return Mono.error(error);
            }
        });
    }

    Mono<McpKeyAuthenticationToken> authentication() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .ofType(McpKeyAuthenticationToken.class)
                .filter(org.springframework.security.core.Authentication::isAuthenticated)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "FORBIDDEN", "A valid MCP access key is required")));
    }

    public Mono<String> username() {
        return authentication().map(McpKeyAuthenticationToken::getName);
    }

    Mono<Set<String>> allowedTools() {
        return authentication().map(McpKeyAuthenticationToken::allowedTools);
    }
}
