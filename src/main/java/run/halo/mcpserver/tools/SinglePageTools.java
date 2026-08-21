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
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class SinglePageTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_single_pages";
    static final String GET = "halo_get_single_page";
    static final String CREATE_PAGE = "halo_create_single_page";
    static final String UPDATE_PAGE = "halo_update_single_page";
    static final String PUBLISH_PAGE = "halo_publish_single_page";
    static final String UNPUBLISH_PAGE = "halo_unpublish_single_page";
    static final String RECYCLE_PAGE = "halo_recycle_single_page";

    private static final int MAX_CONTENT_CHARS = 65_536;
    private static final String DEFAULT_RAW_TYPE = "html";

    private final ReactiveExtensionClient client;
    private final ContentSnapshots snapshots;

    SinglePageTools(
            ReactiveExtensionClient client, ContentSnapshots snapshots, McpAuthorization authorization) {
        super(authorization);
        this.client = client;
        this.snapshots = snapshots;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(
                listTool(),
                getTool(),
                createTool(),
                updateTool(),
                stateTool(PUBLISH_PAGE, "Publish Halo single page", "Publish the current page head snapshot.",
                        "发布页面", "发布独立页面当前的编辑版本。", false, this::publish),
                stateTool(UNPUBLISH_PAGE, "Unpublish Halo single page", "Move a single page back to draft state.",
                        "取消发布页面", "将已发布独立页面恢复为草稿状态。", false, this::unpublish),
                stateTool(RECYCLE_PAGE, "Recycle Halo single page", "Move a single page to the recycle bin.",
                        "回收页面", "将独立页面移入回收站。", true, this::recycle));
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var options = contentOptions(optionalBoolean(arguments, "published"),
                optionalBoolean(arguments, "recycled", false));
        var pageable = PageRequestImpl.of(
                page, size, Sort.by(desc("metadata.creationTimestamp"), asc("metadata.name")));
        return client.listBy(SinglePage.class, options, pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::singlePage),
                "Listed " + result.getItems().size() + " single pages"));
    }

    Mono<ToolPayload> get(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var version = optionalEnum(arguments, "version", ContentVersion.class, ContentVersion.HEAD);
        var format = optionalEnum(arguments, "format", ContentFormat.class, ContentFormat.RAW);
        return client.fetch(SinglePage.class, name)
                .switchIfEmpty(notFound("SinglePage", name))
                .flatMap(page -> {
                    var spec = page.getSpec();
                    var snapshotName = version == ContentVersion.HEAD
                            ? spec.getHeadSnapshot()
                            : spec.getReleaseSnapshot();
                    return snapshots
                            .get(name, spec.getBaseSnapshot(), snapshotName)
                            .map(content -> contentPayload(page, content, format))
                            .onErrorMap(
                                    error -> !(error instanceof McpToolException),
                                    error -> new McpToolException(
                                            "CONTENT_UNAVAILABLE", "Content is unavailable for " + name, error));
                });
    }

    Mono<ToolPayload> create(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var title = requiredString(arguments, "title");
        var slug = optionalString(arguments, "slug", name);
        var content = content(arguments);
        return authorization.username().flatMap(username -> {
            var page = new SinglePage();
            page.setMetadata(metadata(name));
            page.setSpec(spec(arguments, username, title, slug));
            return client.create(page)
                    .flatMap(created -> snapshots.createBase(Ref.of(created), content, username)
                            .flatMap(snapshot -> {
                                var pageSpec = created.getSpec();
                                var snapshotName = snapshot.getMetadata().getName();
                                pageSpec.setBaseSnapshot(snapshotName);
                                pageSpec.setHeadSnapshot(snapshotName);
                                if (Boolean.TRUE.equals(pageSpec.getPublish())) {
                                    pageSpec.setReleaseSnapshot(snapshotName);
                                }
                                return client.update(created);
                            }))
                    .map(created -> payload(ContentPayloads.singlePage(created), "Created single page " + name));
        });
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        var content = content(arguments);
        return authorization.username().flatMap(username -> client.fetch(SinglePage.class, name)
                .switchIfEmpty(notFound("SinglePage", name))
                .flatMap(page -> checkVersion(page.getMetadata().getVersion(), expectedVersion)
                        .then(snapshots.update(
                                Ref.of(page),
                                page.getSpec().getBaseSnapshot(),
                                page.getSpec().getHeadSnapshot(),
                                page.getSpec().getReleaseSnapshot(),
                                content,
                                username))
                        .flatMap(snapshot -> {
                            applyMetadata(page, arguments);
                            page.getSpec().setHeadSnapshot(snapshot.getMetadata().getName());
                            return client.update(page);
                        }))
                .map(updated -> payload(ContentPayloads.singlePage(updated), "Updated single page " + name)));
    }

    Mono<ToolPayload> publish(Map<String, Object> arguments) {
        return updateState(arguments, true, false, "Published single page ");
    }

    Mono<ToolPayload> unpublish(Map<String, Object> arguments) {
        return updateState(arguments, false, false, "Unpublished single page ");
    }

    Mono<ToolPayload> recycle(Map<String, Object> arguments) {
        return updateState(arguments, false, true, "Recycled single page ");
    }

    private Mono<ToolPayload> updateState(
            Map<String, Object> arguments, boolean publish, boolean recycle, String summary) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(SinglePage.class, name)
                .switchIfEmpty(notFound("SinglePage", name))
                .flatMap(page -> checkVersion(page.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
                            var spec = page.getSpec();
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
                            return client.update(page);
                        })))
                .map(page -> payload(ContentPayloads.singlePage(page), summary + name));
    }

    private BuiltInTool listTool() {
        return tool(
                LIST,
                "List Halo single pages",
                "List single pages with deterministic pagination and publication filters.",
                "查询页面",
                "分页查询独立页面，可筛选发布状态和回收站状态。",
                "PAGE",
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
                "Read Halo single page",
                "Read the HEAD or RELEASE content snapshot of one single page.",
                "读取页面",
                "读取指定独立页面的编辑版本或已发布版本，可返回原始内容或渲染结果。",
                "PAGE",
                objectSchema(
                        map(
                                "name", stringSchema("SinglePage metadata.name"),
                                "version", enumSchema(ContentVersion.class, ContentVersion.HEAD.name()),
                                "format", enumSchema(ContentFormat.class, ContentFormat.RAW.name())),
                        List.of("name")),
                permissiveObjectSchema(),
                READ_ONLY,
                this::get);
    }

    private BuiltInTool createTool() {
        return tool(
                CREATE_PAGE,
                "Create Halo single page",
                "Create a draft or published single page with its initial content.",
                "创建页面",
                "创建包含初始内容的草稿或已发布独立页面。",
                "PAGE",
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
                                "allowComment", booleanSchema(true)),
                        List.of("name", "title", "raw")),
                permissiveObjectSchema(),
                CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        return tool(
                UPDATE_PAGE,
                "Update Halo single page",
                "Update single page metadata and its editable content snapshot.",
                "更新页面",
                "更新独立页面标题、别名、可见性、评论设置和编辑内容。",
                "PAGE",
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
                "PAGE",
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
                builder.labelSelector().eq(SinglePage.PUBLISHED_LABEL, Boolean.TRUE.toString());
            } else {
                builder.labelSelector().notEq(SinglePage.PUBLISHED_LABEL, Boolean.TRUE.toString());
            }
        }
        return builder.build();
    }

    private static SinglePage.SinglePageSpec spec(
            Map<String, Object> arguments, String username, String title, String slug) {
        var spec = new SinglePage.SinglePageSpec();
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
        return spec;
    }

    private static void applyMetadata(SinglePage page, Map<String, Object> arguments) {
        var spec = page.getSpec();
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
    }

    private static ContentSnapshots.ContentInput content(Map<String, Object> arguments) {
        var raw = requiredString(arguments, "raw");
        return new ContentSnapshots.ContentInput(
                raw,
                optionalString(arguments, "content", raw),
                optionalString(arguments, "rawType", DEFAULT_RAW_TYPE));
    }

    private static ToolPayload contentPayload(
            SinglePage page, ContentWrapper wrapper, ContentFormat format) {
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
                map("item", ContentPayloads.singlePage(page), "content", content, "truncated", truncated),
                "Read single page " + page.getMetadata().getName());
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
