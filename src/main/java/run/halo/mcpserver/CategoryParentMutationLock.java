package run.halo.mcpserver;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "mcp.halo.run",
        version = "v1alpha1",
        kind = "CategoryParentMutationLock",
        plural = "categoryparentmutationlocks",
        singular = "categoryparentmutationlock")
public class CategoryParentMutationLock extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec = new Spec();

    @Data
    @Schema(name = "CategoryParentMutationLockSpec")
    public static class Spec {
        private String holder;
        private Instant expiresAt;
        private long generation;
    }
}
