package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.equal;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class PostTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_posts";
    static final String GET = "halo_get_post";
    static final String CREATE_POST = "halo_create_post";
    static final String UPDATE_POST = "halo_update_post";
    static final String SET_PUBLISH_STATE = "halo_set_post_publish_state";
    static final String RECYCLE_POST = "halo_recycle_post";
    static final String RESTORE_POST = "halo_restore_post";

    private static final int MAX_CONTENT_CHARS = 65_536;
    private static final String DEFAULT_RAW_TYPE = "html";

    private final ReactiveExtensionClient client;
    private final PostContentService contentService;
    private final ContentSnapshots snapshots;

    PostTools(
            ReactiveExtensionClient client,
            PostContentService contentService,
            ContentSnapshots snapshots,
            McpAuthorization authorization) {
        super(authorization);
        this.client = client;
        this.contentService = contentService;
        this.snapshots = snapshots;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(
                listTool(),
                getTool(),
                createTool(),
                updateTool(),
                publishStateTool(),
                recycleTool(),
                restoreTool());
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var options = contentOptions(optionalBoolean(arguments, "published"),
                optionalBoolean(arguments, "recycled", false));
        var pageable = PageRequestImpl.of(
                page, size, Sort.by(desc("metadata.creationTimestamp"), asc("metadata.name")));
        return client.listBy(Post.class, options, pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::post),
                "Listed " + result.getItems().size() + " posts"));
    }

    Mono<ToolPayload> get(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var version = optionalEnum(arguments, "version", ContentVersion.class, ContentVersion.HEAD);
        var format = optionalEnum(arguments, "format", ContentFormat.class, ContentFormat.RAW);
        return client.fetch(Post.class, name)
                .switchIfEmpty(notFound("Post", name))
                .flatMap(post -> (version == ContentVersion.HEAD
                                ? contentService.getHeadContent(name)
                                : contentService.getReleaseContent(name))
                        .switchIfEmpty(unavailable(name))
                        .map(content -> contentPayload(post, content, format))
                        .onErrorMap(
                                error -> !(error instanceof McpToolException),
                                error -> new McpToolException(
                                        "CONTENT_UNAVAILABLE", "Content is unavailable for " + name, error)));
    }

    Mono<ToolPayload> create(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var title = requiredString(arguments, "title");
        var slug = optionalString(arguments, "slug", name);
        var content = content(arguments);
        return authorization.username().flatMap(username -> {
            var post = new Post();
            post.setMetadata(metadata(name));
            post.setSpec(spec(arguments, username, title, slug));
            return Mono.usingWhen(
                            client.create(post),
                            created -> snapshots.createBase(Ref.of(created), content, username)
                                    .flatMap(snapshot -> {
                                        var snapshotName = snapshot.getMetadata().getName();
                                        return updateLatest(client, Post.class, name, "Post", latest -> {
                                            var postSpec = latest.getSpec();
                                            postSpec.setBaseSnapshot(snapshotName);
                                            postSpec.setHeadSnapshot(snapshotName);
                                            if (Boolean.TRUE.equals(postSpec.getPublish())) {
                                                postSpec.setReleaseSnapshot(snapshotName);
                                            }
                                        });
                                    }),
                            ignored -> Mono.empty(),
                            (created, ignored) -> rollbackCreated(client, created),
                            created -> rollbackCreated(client, created))
                    .map(created -> payload(ContentPayloads.post(created), "Created post " + name));
        });
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        if (!arguments.containsKey("raw")) {
            if (arguments.containsKey("content") || arguments.containsKey("rawType")) {
                throw new McpToolException(
                        "INVALID_ARGUMENT", "raw is required when content or rawType is provided");
            }
            return updateLatest(client, Post.class, name, "Post", post -> applyMetadata(post, arguments))
                    .map(updated -> payload(ContentPayloads.post(updated), "Updated post " + name));
        }
        var content = content(arguments);
        return authorization.username().flatMap(username -> client.fetch(Post.class, name)
                .switchIfEmpty(notFound("Post", name))
                .flatMap(post -> {
                    var expectedHead = post.getSpec().getHeadSnapshot();
                    var expectedRelease = post.getSpec().getReleaseSnapshot();
                    return snapshots.update(
                                Ref.of(post),
                                post.getSpec().getBaseSnapshot(),
                                expectedHead,
                                content,
                                username)
                            .flatMap(snapshot -> Mono.usingWhen(
                                    Mono.just(snapshot),
                                    ignored -> updateLatestIf(
                                            client,
                                            Post.class,
                                            name,
                                            "Post",
                                            latest -> Objects.equals(
                                                            latest.getSpec().getHeadSnapshot(), expectedHead)
                                                    && Objects.equals(
                                                            latest.getSpec().getReleaseSnapshot(), expectedRelease),
                                            () -> new McpToolException(
                                                    "CONFLICT",
                                                    "Post content changed while it was being updated"),
                                            latest -> {
                                                applyMetadata(latest, arguments);
                                                latest.getSpec().setHeadSnapshot(
                                                        snapshot.getMetadata().getName());
                                    }),
                                    ignored -> Mono.empty(),
                                    (created, ignored) -> snapshots.rollbackUnlessReferenced(
                                            created,
                                            client.fetch(Post.class, name)
                                                    .map(latest -> {
                                                        var snapshotName = created.getMetadata().getName();
                                                        return Objects.equals(
                                                                        latest.getSpec().getHeadSnapshot(),
                                                                        snapshotName)
                                                                || Objects.equals(
                                                                        latest.getSpec().getReleaseSnapshot(),
                                                                        snapshotName);
                                                    })),
                                    created -> Mono.empty()));
                })
                .map(updated -> payload(ContentPayloads.post(updated), "Updated post " + name)));
    }

    Mono<ToolPayload> setPublishState(Map<String, Object> arguments) {
        var publish = requiredBoolean(arguments, "publish");
        return updateState(
                arguments, publish, false, (publish ? "Published post " : "Unpublished post "));
    }

    Mono<ToolPayload> recycle(Map<String, Object> arguments) {
        return updateState(arguments, false, true, "Recycled post ");
    }

    Mono<ToolPayload> restore(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        return updateLatest(client, Post.class, name, "Post", post -> post.getSpec().setDeleted(false))
                .map(post -> payload(ContentPayloads.post(post), "Restored post " + name));
    }

    private Mono<ToolPayload> updateState(
            Map<String, Object> arguments, boolean publish, boolean recycle, String summary) {
        var name = resourceName(arguments, "name");
        return updateLatest(client, Post.class, name, "Post", post -> {
                    var spec = post.getSpec();
                    if (recycle) {
                        spec.setDeleted(true);
                    } else {
                        spec.setPublish(publish);
                        if (publish) {
                            if (spec.getHeadSnapshot() == null) {
                                spec.setHeadSnapshot(spec.getBaseSnapshot());
                            }
                            spec.setReleaseSnapshot(spec.getHeadSnapshot());
                        }
                    }
                })
                .map(post -> payload(ContentPayloads.post(post), summary + name));
    }

    private BuiltInTool listTool() {
        return tool(
                LIST,
                "List Halo posts",
                "List posts with deterministic pagination and publication filters.",
                "查询文章",
                "分页查询文章，可筛选发布状态和回收站状态。",
                "POST",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE),
                                "published", booleanSchema(),
                                "recycled", booleanSchema(false)),
                        List.of()),
                pageOutputSchema(ContentPayloads.postSchema()),
                READ_ONLY,
                this::list);
    }

    private BuiltInTool getTool() {
        return tool(
                GET,
                "Read Halo post",
                "Read the HEAD or RELEASE content snapshot of one post.",
                "读取文章",
                "读取指定文章的编辑版本或已发布版本，可返回原始内容或渲染结果。",
                "POST",
                objectSchema(
                        map(
                                "name", stringSchema("Post metadata.name"),
                                "version", enumSchema(ContentVersion.class, ContentVersion.HEAD.name()),
                                "format", enumSchema(ContentFormat.class, ContentFormat.RAW.name())),
                        List.of("name")),
                contentOutputSchema(ContentPayloads.postSchema()),
                READ_ONLY,
                this::get);
    }

    private BuiltInTool createTool() {
        return tool(
                CREATE_POST,
                "Create Halo post",
                "Create a draft or published post with its initial content.",
                "创建文章",
                "创建包含初始内容的草稿或已发布文章。",
                "POST",
                objectSchema(
                        map(
                                "name", stringSchema("Post metadata.name (a lowercase DNS label)."),
                                "title", stringSchema(),
                                "slug", stringSchema(),
                                "raw", stringSchema("Editable source content stored in the snapshot."),
                                "content", stringSchema("Rendered content; defaults to raw when omitted."),
                                "rawType", described(
                                        stringSchemaWithDefault(DEFAULT_RAW_TYPE),
                                        "Format identifier for raw, such as html or markdown."),
                                "publish", described(
                                        booleanSchema(false),
                                        "Whether to publish the initial snapshot immediately."),
                                "visible", enumSchema(Post.VisibleEnum.class, Post.VisibleEnum.PUBLIC.name()),
                                "allowComment", booleanSchema(true),
                                "cover", nullableStringSchema(),
                                "template", nullableStringSchema(),
                                "excerpt", nullableStringSchema(),
                                "autoGenerateExcerpt", booleanSchema(),
                                "pinned", booleanSchema(false),
                                "priority", nonNegativeIntegerSchema(0),
                                "publishTime", nullableInstantSchema(),
                                "categories", arrayStringSchema(),
                                "tags", arrayStringSchema()),
                        List.of("name", "title", "raw")),
                ContentPayloads.postSchema(),
                CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        return tool(
                UPDATE_POST,
                "Update Halo post",
                "Update post metadata and, when raw is provided, its editable content snapshot.",
                "更新文章",
                "更新文章元数据；传入 raw 时同时更新编辑内容，否则不会创建新快照。",
                "POST",
                objectSchema(
                        map(
                                "name", stringSchema("Post metadata.name."),
                                "title", stringSchema(),
                                "slug", stringSchema(),
                                "raw", stringSchema("New editable source; omit for a metadata-only update."),
                                "content", stringSchema("Rendered content; valid only when raw is provided."),
                                "rawType", described(
                                        stringSchemaWithDefault(DEFAULT_RAW_TYPE),
                                        "Format identifier for raw; valid only when raw is provided."),
                                "visible", enumSchema(Post.VisibleEnum.class),
                                "allowComment", booleanSchema(),
                                "cover", nullableStringSchema(),
                                "template", nullableStringSchema(),
                                "excerpt", nullableStringSchema(),
                                "autoGenerateExcerpt", booleanSchema(),
                                "pinned", booleanSchema(),
                                "priority", nonNegativeIntegerSchema(),
                                "publishTime", nullableInstantSchema(),
                                "categories", arrayStringSchema(),
                                "tags", arrayStringSchema()),
                        List.of("name")),
                ContentPayloads.postSchema(),
                UPDATE,
                this::update);
    }

    private BuiltInTool recycleTool() {
        return tool(
                RECYCLE_POST,
                "Recycle Halo post",
                "Move a post to the recycle bin.",
                "回收文章",
                "将文章移入回收站。",
                "POST",
                objectSchema(
                        map("name", stringSchema()),
                        List.of("name")),
                ContentPayloads.postSchema(),
                DESTRUCTIVE,
                this::recycle);
    }

    private BuiltInTool publishStateTool() {
        return tool(
                SET_PUBLISH_STATE,
                "Set Halo post publish state",
                "Publish the current post head snapshot or move the post back to draft state.",
                "修改文章发布状态",
                "设置文章为发布或草稿状态；发布时使用当前编辑版本。",
                "POST",
                objectSchema(
                        map(
                                "name", stringSchema("Post metadata.name."),
                                "publish", described(
                                        booleanSchema(),
                                        "True publishes the current head snapshot; false returns the post to draft.")),
                        List.of("name", "publish")),
                ContentPayloads.postSchema(),
                UPDATE,
                this::setPublishState);
    }

    private BuiltInTool restoreTool() {
        return tool(
                RESTORE_POST,
                "Restore Halo post",
                "Restore a post from the recycle bin.",
                "恢复文章",
                "将文章从回收站恢复。",
                "POST",
                objectSchema(map("name", stringSchema()), List.of("name")),
                ContentPayloads.postSchema(),
                RESTORE,
                this::restore);
    }

    private static ListOptions contentOptions(Boolean published, boolean recycled) {
        var builder = ListOptions.builder().fieldQuery(equal("spec.deleted", recycled));
        if (published != null) {
            if (published) {
                builder.labelSelector().eq(Post.PUBLISHED_LABEL, Boolean.TRUE.toString());
            } else {
                builder.labelSelector().notEq(Post.PUBLISHED_LABEL, Boolean.TRUE.toString());
            }
        }
        return builder.build();
    }

    private static Post.PostSpec spec(
            Map<String, Object> arguments, String username, String title, String slug) {
        var spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setSlug(slug);
        spec.setOwner(username);
        spec.setDeleted(false);
        spec.setPublish(optionalBoolean(arguments, "publish", false));
        spec.setVisible(optionalEnum(arguments, "visible", Post.VisibleEnum.class, Post.VisibleEnum.PUBLIC));
        spec.setAllowComment(optionalBoolean(arguments, "allowComment", true));
        spec.setCover(nullableString(arguments, "cover"));
        spec.setTemplate(nullableString(arguments, "template"));
        spec.setPinned(optionalBoolean(arguments, "pinned", false));
        spec.setPriority(optionalNonNegativeInt(arguments, "priority", 0));
        spec.setPublishTime(optionalInstant(arguments, "publishTime"));
        spec.setExcerpt(excerpt(arguments, null));
        spec.setCategories(stringList(arguments, "categories"));
        spec.setTags(stringList(arguments, "tags"));
        return spec;
    }

    private static void applyMetadata(Post post, Map<String, Object> arguments) {
        var spec = post.getSpec();
        if (arguments.containsKey("title")) {
            spec.setTitle(requiredString(arguments, "title"));
        }
        if (arguments.containsKey("slug")) {
            spec.setSlug(requiredString(arguments, "slug"));
        }
        if (arguments.containsKey("visible")) {
            spec.setVisible(requiredEnum(arguments, "visible", Post.VisibleEnum.class));
        }
        if (arguments.containsKey("allowComment")) {
            spec.setAllowComment(optionalBoolean(arguments, "allowComment", true));
        }
        if (arguments.containsKey("cover")) {
            spec.setCover(nullableString(arguments, "cover"));
        }
        if (arguments.containsKey("template")) {
            spec.setTemplate(nullableString(arguments, "template"));
        }
        if (arguments.containsKey("excerpt") || arguments.containsKey("autoGenerateExcerpt")) {
            spec.setExcerpt(excerpt(arguments, spec.getExcerpt()));
        }
        if (arguments.containsKey("pinned")) {
            spec.setPinned(optionalBoolean(arguments, "pinned", false));
        }
        if (arguments.containsKey("priority")) {
            spec.setPriority(optionalNonNegativeInt(arguments, "priority", 0));
        }
        if (arguments.containsKey("publishTime")) {
            spec.setPublishTime(optionalInstant(arguments, "publishTime"));
        }
        if (arguments.containsKey("categories")) {
            spec.setCategories(stringList(arguments, "categories"));
        }
        if (arguments.containsKey("tags")) {
            spec.setTags(stringList(arguments, "tags"));
        }
    }

    private static ContentSnapshots.ContentInput content(Map<String, Object> arguments) {
        var raw = requiredString(arguments, "raw");
        return new ContentSnapshots.ContentInput(
                raw,
                optionalString(arguments, "content", raw),
                optionalString(arguments, "rawType", DEFAULT_RAW_TYPE));
    }

    private static Post.Excerpt excerpt(Map<String, Object> arguments, Post.Excerpt current) {
        var excerpt = current == null ? new Post.Excerpt() : current;
        if (arguments.containsKey("excerpt")) {
            excerpt.setRaw(nullableString(arguments, "excerpt"));
        }
        final boolean autoGenerate;
        if (arguments.containsKey("autoGenerateExcerpt")) {
            autoGenerate = optionalBoolean(arguments, "autoGenerateExcerpt", true);
        } else if (arguments.containsKey("excerpt")) {
            autoGenerate = false;
        } else {
            autoGenerate = current == null || Boolean.TRUE.equals(current.getAutoGenerate());
        }
        excerpt.setAutoGenerate(autoGenerate);
        return excerpt;
    }

    private static Instant optionalInstant(Map<String, Object> arguments, String name) {
        var value = nullableString(arguments, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new McpToolException("INVALID_ARGUMENT", name + " must be an ISO-8601 instant", error);
        }
    }

    private static Map<String, Object> nullableInstantSchema() {
        var schema = new LinkedHashMap<>(HaloSchemaProperties.property(Post.PostSpec.class, "publishTime"));
        schema.put("type", List.of("string", "null"));
        return schema;
    }

    private static ToolPayload contentPayload(Post post, ContentWrapper wrapper, ContentFormat format) {
        var content = new LinkedHashMap<String, Object>();
        content.put("snapshotName", wrapper.getSnapshotName());
        content.put("rawType", wrapper.getRawType());
        var truncated = false;
        if (format == ContentFormat.RAW || format == ContentFormat.BOTH) {
            var raw = truncate(wrapper.getRaw());
            content.put("raw", raw.value());
            truncated = raw.truncated();
        }
        if (format == ContentFormat.RENDERED || format == ContentFormat.BOTH) {
            var rendered = truncate(wrapper.getContent());
            content.put("rendered", rendered.value());
            truncated = truncated || rendered.truncated();
        }
        return payload(
                map("item", ContentPayloads.post(post), "content", content, "truncated", truncated),
                "Read post " + post.getMetadata().getName());
    }

    private static TruncatedText truncate(String value) {
        if (value == null || value.length() <= MAX_CONTENT_CHARS) {
            return new TruncatedText(value, false);
        }
        return new TruncatedText(value.substring(0, MAX_CONTENT_CHARS), true);
    }

    enum ContentVersion {
        HEAD,
        RELEASE
    }

    enum ContentFormat {
        RAW,
        RENDERED,
        BOTH
    }

    private record TruncatedText(String value, boolean truncated) {}
}
