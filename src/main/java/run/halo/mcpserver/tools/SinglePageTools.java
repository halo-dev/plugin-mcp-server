package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.equal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    static final String SET_PUBLISH_STATE = "halo_set_single_page_publish_state";
    static final String RECYCLE_PAGE = "halo_recycle_single_page";
    static final String RESTORE_PAGE = "halo_restore_single_page";

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
            return Mono.usingWhen(
                            client.create(page),
                            created -> snapshots.createBase(Ref.of(created), content, username)
                                    .flatMap(snapshot -> {
                                        var snapshotName = snapshot.getMetadata().getName();
                                        return updateLatest(client, SinglePage.class, name, "SinglePage", latest -> {
                                            var pageSpec = latest.getSpec();
                                            pageSpec.setBaseSnapshot(snapshotName);
                                            pageSpec.setHeadSnapshot(snapshotName);
                                            if (Boolean.TRUE.equals(pageSpec.getPublish())) {
                                                pageSpec.setReleaseSnapshot(snapshotName);
                                            }
                                        });
                                    }),
                            ignored -> Mono.empty(),
                            (created, ignored) -> rollbackCreated(client, created),
                            created -> rollbackCreated(client, created))
                    .map(created -> payload(ContentPayloads.singlePage(created), "Created single page " + name));
        });
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        if (!arguments.containsKey("raw")) {
            if (arguments.containsKey("content") || arguments.containsKey("rawType")) {
                throw new McpToolException(
                        "INVALID_ARGUMENT", "raw is required when content or rawType is provided");
            }
            return updateLatest(
                            client,
                            SinglePage.class,
                            name,
                            "SinglePage",
                            page -> applyMetadata(page, arguments))
                    .map(updated -> payload(
                            ContentPayloads.singlePage(updated), "Updated single page " + name));
        }
        var content = content(arguments);
        return authorization.username().flatMap(username -> client.fetch(SinglePage.class, name)
                .switchIfEmpty(notFound("SinglePage", name))
                .flatMap(page -> {
                    var expectedHead = page.getSpec().getHeadSnapshot();
                    var expectedRelease = page.getSpec().getReleaseSnapshot();
                    return snapshots.update(
                                Ref.of(page),
                                page.getSpec().getBaseSnapshot(),
                                expectedHead,
                                content,
                                username)
                            .flatMap(snapshot -> Mono.usingWhen(
                                    Mono.just(snapshot),
                                    ignored -> updateLatestIf(
                                            client,
                                            SinglePage.class,
                                            name,
                                            "SinglePage",
                                            latest -> Objects.equals(
                                                            latest.getSpec().getHeadSnapshot(), expectedHead)
                                                    && Objects.equals(
                                                            latest.getSpec().getReleaseSnapshot(), expectedRelease),
                                            () -> new McpToolException(
                                                    "CONFLICT",
                                                    "Single page content changed while it was being updated"),
                                            latest -> {
                                                applyMetadata(latest, arguments);
                                                latest.getSpec().setHeadSnapshot(
                                                        snapshot.getMetadata().getName());
                                    }),
                                    ignored -> Mono.empty(),
                                    (created, ignored) -> snapshots.rollbackUnlessReferenced(
                                            created,
                                            client.fetch(SinglePage.class, name)
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
                .map(updated -> payload(ContentPayloads.singlePage(updated), "Updated single page " + name)));
    }

    Mono<ToolPayload> setPublishState(Map<String, Object> arguments) {
        var publish = requiredBoolean(arguments, "publish");
        return updateState(
                arguments,
                publish,
                false,
                publish ? "Published single page " : "Unpublished single page ");
    }

    Mono<ToolPayload> recycle(Map<String, Object> arguments) {
        return updateState(arguments, false, true, "Recycled single page ");
    }

    Mono<ToolPayload> restore(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        return updateLatest(
                        client,
                        SinglePage.class,
                        name,
                        "SinglePage",
                        page -> page.getSpec().setDeleted(false))
                .map(page -> payload(ContentPayloads.singlePage(page), "Restored single page " + name));
    }

    private Mono<ToolPayload> updateState(
            Map<String, Object> arguments, boolean publish, boolean recycle, String summary) {
        var name = resourceName(arguments, "name");
        return updateLatest(client, SinglePage.class, name, "SinglePage", page -> {
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
                })
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
                pageOutputSchema(ContentPayloads.singlePageSchema()),
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
                contentOutputSchema(ContentPayloads.singlePageSchema()),
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
                                "name", stringSchema("SinglePage metadata.name (a lowercase DNS label)."),
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
                                "allowComment", booleanSchema(true)),
                        List.of("name", "title", "raw")),
                ContentPayloads.singlePageSchema(),
                CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        return tool(
                UPDATE_PAGE,
                "Update Halo single page",
                "Update single page metadata and, when raw is provided, its editable content snapshot.",
                "更新页面",
                "更新独立页面标题、别名、可见性和评论设置；传入 raw 时同时更新编辑内容。",
                "PAGE",
                objectSchema(
                        map(
                                "name", stringSchema("SinglePage metadata.name."),
                                "title", stringSchema(),
                                "slug", stringSchema(),
                                "raw", stringSchema("New editable source; omit for a metadata-only update."),
                                "content", stringSchema("Rendered content; valid only when raw is provided."),
                                "rawType", described(
                                        stringSchemaWithDefault(DEFAULT_RAW_TYPE),
                                        "Format identifier for raw; valid only when raw is provided."),
                                "visible", enumSchema(Post.VisibleEnum.class),
                                "allowComment", booleanSchema()),
                        List.of("name")),
                ContentPayloads.singlePageSchema(),
                UPDATE,
                this::update);
    }

    private BuiltInTool recycleTool() {
        return tool(
                RECYCLE_PAGE,
                "Recycle Halo single page",
                "Move a single page to the recycle bin.",
                "回收页面",
                "将独立页面移入回收站。",
                "PAGE",
                objectSchema(
                        map("name", stringSchema()),
                        List.of("name")),
                ContentPayloads.singlePageSchema(),
                DESTRUCTIVE,
                this::recycle);
    }

    private BuiltInTool publishStateTool() {
        return tool(
                SET_PUBLISH_STATE,
                "Set Halo single page publish state",
                "Publish the current page head snapshot or move the page back to draft state.",
                "修改页面发布状态",
                "设置独立页面为发布或草稿状态；发布时使用当前编辑版本。",
                "PAGE",
                objectSchema(
                        map(
                                "name", stringSchema("SinglePage metadata.name."),
                                "publish", described(
                                        booleanSchema(),
                                        "True publishes the current head snapshot; false returns the page to draft.")),
                        List.of("name", "publish")),
                ContentPayloads.singlePageSchema(),
                UPDATE,
                this::setPublishState);
    }

    private BuiltInTool restoreTool() {
        return tool(
                RESTORE_PAGE,
                "Restore Halo single page",
                "Restore a single page from the recycle bin.",
                "恢复页面",
                "将独立页面从回收站恢复。",
                "PAGE",
                objectSchema(map("name", stringSchema()), List.of("name")),
                ContentPayloads.singlePageSchema(),
                RESTORE,
                this::restore);
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
