package run.halo.mcpserver;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "mcp.halo.run",
        version = "v1alpha1",
        kind = "McpAccessKey",
        plural = "mcpaccesskeys",
        singular = "mcpaccesskey")
public class McpAccessKey extends AbstractExtension {

    static final String ALLOWED_TOOLS_DESCRIPTION =
            "Allowed MCP tool names. Use '*' to automatically allow all current and future tools.";

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec = new Spec();

    private Status status = new Status();

    @Data
    @Schema(name = "McpAccessKeySpec")
    public static class Spec {
        private String displayName;
        private String keyHash;
        private String keyPrefix;
        private String ownerName;
        private boolean enabled = true;
        private Instant expiresAt;
        @Schema(description = ALLOWED_TOOLS_DESCRIPTION)
        private Set<String> allowedTools = new LinkedHashSet<>();
        @Schema(description = "Allowed IPv4/IPv6 addresses or CIDR ranges. Empty means unrestricted.")
        private Set<String> allowedIpRanges = new LinkedHashSet<>();
    }

    @Data
    @Schema(name = "McpAccessKeyStatus")
    public static class Status {
        private Instant lastUsedAt;
    }
}
