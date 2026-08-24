package run.halo.mcpserver.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Bounds attachment uploads retained by this plugin instance. */
@Component
final class AttachmentUploadLimiter {

    static final long GLOBAL_BYTE_LIMIT = 32L * 1024 * 1024;
    static final long PER_KEY_BYTE_LIMIT = 16L * 1024 * 1024;
    static final int GLOBAL_UPLOAD_LIMIT = 4;
    static final int PER_KEY_UPLOAD_LIMIT = 2;
    static final int PER_POLICY_UPLOAD_LIMIT = 2;

    private final long globalByteLimit;
    private final long perKeyByteLimit;
    private final int globalUploadLimit;
    private final int perKeyUploadLimit;
    private final int perPolicyUploadLimit;
    private final Map<String, Usage> activeByKey = new HashMap<>();
    private final Map<String, Integer> activeByPolicy = new HashMap<>();
    private long activeBytes;
    private int activeUploads;

    AttachmentUploadLimiter() {
        this(
                GLOBAL_BYTE_LIMIT,
                PER_KEY_BYTE_LIMIT,
                GLOBAL_UPLOAD_LIMIT,
                PER_KEY_UPLOAD_LIMIT,
                PER_POLICY_UPLOAD_LIMIT);
    }

    AttachmentUploadLimiter(
            long globalByteLimit,
            long perKeyByteLimit,
            int globalUploadLimit,
            int perKeyUploadLimit,
            int perPolicyUploadLimit) {
        if (globalByteLimit < 1
                || perKeyByteLimit < 1
                || perKeyByteLimit > globalByteLimit
                || globalUploadLimit < 1
                || perKeyUploadLimit < 1
                || perKeyUploadLimit > globalUploadLimit
                || perPolicyUploadLimit < 1
                || perPolicyUploadLimit > globalUploadLimit) {
            throw new IllegalArgumentException("Invalid attachment upload limits");
        }
        this.globalByteLimit = globalByteLimit;
        this.perKeyByteLimit = perKeyByteLimit;
        this.globalUploadLimit = globalUploadLimit;
        this.perKeyUploadLimit = perKeyUploadLimit;
        this.perPolicyUploadLimit = perPolicyUploadLimit;
    }

    synchronized Optional<Permit> tryAcquire(String keyId, String policyName, int contentBytes) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(policyName, "policyName must not be null");
        if (contentBytes < 1) {
            throw new IllegalArgumentException("contentBytes must be positive");
        }
        var keyUsage = activeByKey.getOrDefault(keyId, Usage.EMPTY);
        var policyUploads = activeByPolicy.getOrDefault(policyName, 0);
        if (activeBytes + contentBytes > globalByteLimit
                || keyUsage.bytes + contentBytes > perKeyByteLimit
                || activeUploads >= globalUploadLimit
                || keyUsage.uploads >= perKeyUploadLimit
                || policyUploads >= perPolicyUploadLimit) {
            return Optional.empty();
        }
        activeBytes += contentBytes;
        activeUploads++;
        activeByKey.put(keyId, new Usage(keyUsage.bytes + contentBytes, keyUsage.uploads + 1));
        activeByPolicy.put(policyName, policyUploads + 1);
        return Optional.of(new Permit(this, keyId, policyName, contentBytes));
    }

    private synchronized void release(String keyId, String policyName, int contentBytes) {
        var keyUsage = activeByKey.get(keyId);
        var policyUploads = activeByPolicy.get(policyName);
        if (keyUsage == null || policyUploads == null) {
            return;
        }
        activeBytes -= contentBytes;
        activeUploads--;
        if (keyUsage.uploads == 1) {
            activeByKey.remove(keyId);
        } else {
            activeByKey.put(
                    keyId, new Usage(keyUsage.bytes - contentBytes, keyUsage.uploads - 1));
        }
        if (policyUploads == 1) {
            activeByPolicy.remove(policyName);
        } else {
            activeByPolicy.put(policyName, policyUploads - 1);
        }
    }

    private record Usage(long bytes, int uploads) {

        private static final Usage EMPTY = new Usage(0, 0);
    }

    static final class Permit implements AutoCloseable {

        private final AttachmentUploadLimiter limiter;
        private final String keyId;
        private final String policyName;
        private final int contentBytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(
                AttachmentUploadLimiter limiter,
                String keyId,
                String policyName,
                int contentBytes) {
            this.limiter = limiter;
            this.keyId = keyId;
            this.policyName = policyName;
            this.contentBytes = contentBytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                limiter.release(keyId, policyName, contentBytes);
            }
        }
    }
}
