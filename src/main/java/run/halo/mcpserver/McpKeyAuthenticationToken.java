package run.halo.mcpserver;

import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class McpKeyAuthenticationToken extends AbstractAuthenticationToken {

    static final String ALL_TOOLS = "*";

    private final String keyId;
    private final String keyDisplayName;
    private final String keyPrefix;
    private final String ownerName;
    private final Set<String> allowedTools;

    McpKeyAuthenticationToken(
            String keyId,
            String keyDisplayName,
            String keyPrefix,
            String ownerName,
            Set<String> allowedTools) {
        super(List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        this.keyId = keyId;
        this.keyDisplayName = keyDisplayName;
        this.keyPrefix = keyPrefix;
        this.ownerName = ownerName;
        this.allowedTools = Set.copyOf(allowedTools);
        setAuthenticated(true);
    }

    String keyId() {
        return keyId;
    }

    String keyDisplayName() {
        return keyDisplayName;
    }

    String keyPrefix() {
        return keyPrefix;
    }

    Set<String> allowedTools() {
        return allowedTools;
    }

    boolean allows(String toolName) {
        return allowedTools.contains(ALL_TOOLS) || allowedTools.contains(toolName);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return ownerName;
    }
}
