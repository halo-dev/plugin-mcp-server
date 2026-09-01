package run.halo.mcpserver.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

@Component
class ContentSnapshots {

    private final ReactiveExtensionClient client;

    ContentSnapshots(ReactiveExtensionClient client) {
        this.client = client;
    }

    Mono<Snapshot> createBase(Ref subjectRef, ContentInput content, String username) {
        var snapshot = snapshot(subjectRef, content, username);
        var metadata = snapshot.getMetadata();
        metadata.setAnnotations(new LinkedHashMap<>(Map.of(Snapshot.KEEP_RAW_ANNO, "true")));
        return client.create(snapshot);
    }

    Mono<Snapshot> update(
            Ref subjectRef,
            String baseSnapshotName,
            String headSnapshotName,
            ContentInput content,
            String username) {
        return client.fetch(Snapshot.class, baseSnapshotName)
                .switchIfEmpty(ToolSupport.unavailable(subjectRef.getName()))
                .flatMap(base -> {
                    var next = snapshot(subjectRef, content, username);
                    next.getSpec().setParentSnapshotName(headSnapshotName);
                    applyContent(next, base, content);
                    return client.create(next);
                });
    }

    Mono<ContentWrapper> get(String name, String baseSnapshotName, String snapshotName) {
        if (!StringUtils.hasText(baseSnapshotName) || !StringUtils.hasText(snapshotName)) {
            return ToolSupport.unavailable(name);
        }
        return client.fetch(Snapshot.class, baseSnapshotName)
                .switchIfEmpty(ToolSupport.unavailable(name))
                .flatMap(base -> Objects.equals(baseSnapshotName, snapshotName)
                        ? Mono.just(ContentWrapper.patchSnapshot(base, base))
                        : client.fetch(Snapshot.class, snapshotName)
                                .switchIfEmpty(ToolSupport.unavailable(name))
                                .map(snapshot -> ContentWrapper.patchSnapshot(snapshot, base)));
    }

    Mono<Void> rollback(Snapshot snapshot) {
        return ToolSupport.rollbackCreated(client, snapshot);
    }

    Mono<Void> rollbackUnlessReferenced(Snapshot snapshot, Mono<Boolean> referencedByOwner) {
        return referencedByOwner
                .defaultIfEmpty(false)
                .flatMap(referenced -> referenced ? Mono.empty() : rollback(snapshot))
                .onErrorResume(ignored -> Mono.empty());
    }

    private static Snapshot snapshot(Ref subjectRef, ContentInput content, String username) {
        var snapshot = new Snapshot();
        snapshot.setMetadata(ToolSupport.metadata(UUID.randomUUID().toString()));
        var spec = new Snapshot.SnapShotSpec();
        spec.setSubjectRef(subjectRef);
        spec.setRawType(content.rawType());
        spec.setRawPatch(content.raw());
        spec.setContentPatch(content.content());
        spec.setLastModifyTime(Instant.now());
        spec.setOwner(username);
        snapshot.setSpec(spec);
        Snapshot.addContributor(snapshot, username);
        return snapshot;
    }

    private static void applyContent(Snapshot snapshot, Snapshot base, ContentInput content) {
        var baseName = base.getMetadata().getName();
        var spec = snapshot.getSpec();
        spec.setRawType(content.rawType());
        spec.setLastModifyTime(Instant.now());
        if (Objects.equals(baseName, snapshot.getMetadata().getName())) {
            spec.setRawPatch(content.raw());
            spec.setContentPatch(content.content());
            var annotations = snapshot.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new LinkedHashMap<>();
                snapshot.getMetadata().setAnnotations(annotations);
            }
            annotations.put(Snapshot.KEEP_RAW_ANNO, "true");
            return;
        }
        spec.setRawPatch(PatchUtils.diffToJsonPatch(base.getSpec().getRawPatch(), content.raw()));
        spec.setContentPatch(PatchUtils.diffToJsonPatch(base.getSpec().getContentPatch(), content.content()));
    }

    record ContentInput(String raw, String content, String rawType) {}
}
