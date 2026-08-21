package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.equal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static final String PUBLISH_POST = "halo_publish_post";
    static final String UNPUBLISH_POST = "halo_unpublish_post";
    static final String RECYCLE_POST = "halo_recycle_post";

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
                stateTool(PUBLISH_POST, "Publish Halo post", "Publish the current post head snapshot.",
                        "发布文章", "发布文章当前的编辑版本。", false, this::publish),
                stateTool(UNPUBLISH_POST, "Unpublish Halo post", "Move a post back to draft state.",
                        "取消发布文章", "将已发布文章恢复为草稿状态。", false, this::unpublish),
                stateTool(RECYCLE_POST, "Recycle Halo post", "Move a post to the recycle bin.",
                        "回收文章", "将文章移入回收站。", true, this::recycle));
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
            return client.create(post)
                    .flatMap(created -> snapshots.createBase(Ref.of(created), content, username)
                            .flatMap(snapshot -> {
                                var postSpec = created.getSpec();
                                var snapshotName = snapshot.getMetadata().getName();
                                postSpec.setBaseSnapshot(snapshotName);
                                postSpec.setHeadSnapshot(snapshotName);
                                if (Boolean.TRUE.equals(postSpec.getPublish())) {
                                    postSpec.setReleaseSnapshot(snapshotName);
                                }
                                return client.update(created);
                            }))
                    .map(created -> payload(ContentPayloads.post(created), "Created post " + name));
        });
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        var content = content(arguments);
        return authorization.username().flatMap(username -> client.fetch(Post.class, name)
                .switchIfEmpty(notFound("Post", name))
                .flatMap(post -> checkVersion(post.getMetadata().getVersion(), expectedVersion)
                        .then(snapshots.update(
                                Ref.of(post),
                                post.getSpec().getBaseSnapshot(),
                                post.getSpec().getHeadSnapshot(),
                                post.getSpec().getReleaseSnapshot(),
                                content,
                                username))
                        .flatMap(snapshot -> {
                            applyMetadata(post, arguments);
                            post.getSpec().setHeadSnapshot(snapshot.getMetadata().getName());
                            return client.update(post);
                        }))
                .map(updated -> payload(ContentPayloads.post(updated), "Updated post " + name)));
    }

    Mono<ToolPayload> publish(Map<String, Object> arguments) {
        return updateState(arguments, true, false, "Published post ");
    }

    Mono<ToolPayload> unpublish(Map<String, Object> arguments) {
        return updateState(arguments, false, false, "Unpublished post ");
    }

    Mono<ToolPayload> recycle(Map<String, Object> arguments) {
        return updateState(arguments, false, true, "Recycled post ");
    }

    private Mono<ToolPayload> updateState(
            Map<String, Object> arguments, boolean publish, boolean recycle, String summary) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Post.class, name)
                .switchIfEmpty(notFound("Post", name))
                .flatMap(post -> checkVersion(post.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
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
                            return client.update(post);
                        })))
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
                pageOutputSchema(),
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
                permissiveObjectSchema(),
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
                                "name", stringSchema(),
                                "title", stringSchema(),
                                "slug", stringSchema(),
                                "raw", stringSchema(),
                                "content", stringSchema(),
                                "rawType", stringSchemaWithDefault(DEFAULT_RAW_TYPE),
                                "publish", booleanSchema(false),
                                "visible", enumSchema(Post.VisibleEnum.class, Post.VisibleEnum.PUBLIC.name()),
                                "allowComment", booleanSchema(true),
                                "categories", arrayStringSchema(),
                                "tags", arrayStringSchema()),
                        List.of("name", "title", "raw")),
                permissiveObjectSchema(),
                CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        return tool(
                UPDATE_POST,
                "Update Halo post",
                "Update post metadata and its editable content snapshot.",
                "更新文章",
                "更新文章标题、别名、分类、标签、可见性、评论设置和编辑内容。",
                "POST",
                objectSchema(
                        map(
                                "name", stringSchema(),
                                "title", stringSchema(),
                                "slug", stringSchema(),
                                "raw", stringSchema(),
                                "content", stringSchema(),
                                "rawType", stringSchemaWithDefault(DEFAULT_RAW_TYPE),
                                "visible", enumSchema(Post.VisibleEnum.class),
                                "allowComment", booleanSchema(),
                                "categories", arrayStringSchema(),
                                "tags", arrayStringSchema(),
                                "expectedVersion", integerSchema()),
                        List.of("name", "raw")),
                permissiveObjectSchema(),
                UPDATE,
                this::update);
    }

    private BuiltInTool stateTool(
            String name,
            String title,
            String description,
            String displayTitle,
            String displayDescription,
            boolean destructive,
            java.util.function.Function<Map<String, Object>, Mono<ToolPayload>> handler) {
        return tool(
                name,
                title,
                description,
                displayTitle,
                displayDescription,
                "POST",
                objectSchema(
                        map("name", stringSchema(), "expectedVersion", integerSchema()),
                        List.of("name")),
                permissiveObjectSchema(),
                destructive ? DESTRUCTIVE : UPDATE,
                handler);
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
        spec.setPinned(false);
        spec.setPriority(0);
        var excerpt = new Post.Excerpt();
        excerpt.setAutoGenerate(true);
        spec.setExcerpt(excerpt);
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
