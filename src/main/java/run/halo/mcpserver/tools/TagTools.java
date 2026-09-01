package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.or;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class TagTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_tags";
    static final String CREATE = "halo_create_tag";
    static final String UPDATE = "halo_update_tag";
    static final String DELETE = "halo_delete_tag";

    private final ReactiveExtensionClient client;

    TagTools(ReactiveExtensionClient client, McpAuthorization authorization) {
        super(authorization);
        this.client = client;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(tool(
                LIST,
                "List Halo tags",
                "List post tags with display metadata and post counters.",
                "查询标签",
                "分页查询文章标签，返回显示信息和文章数量。",
                "TAG",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE),
                                "keyword", stringSchema()),
                        List.of()),
                pageOutputSchema(ContentPayloads.tagSchema()),
                READ_ONLY,
                this::list), createTool(), updateTool(), deleteTool());
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var builder = ListOptions.builder().fieldQuery(ExtensionUtil.notDeleting());
        var keyword = optionalString(arguments, "keyword", null);
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(or(contains("spec.displayName", keyword), contains("spec.slug", keyword)));
        }
        var pageable = PageRequestImpl.of(
                page, size, Sort.by(asc("spec.displayName"), asc("metadata.name")));
        return client.listBy(Tag.class, builder.build(), pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::tag),
                "Listed " + result.getItems().size() + " tags"));
    }

    Mono<ToolPayload> create(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var tag = new Tag();
        tag.setMetadata(metadata(name));
        tag.setSpec(new Tag.TagSpec());
        apply(tag.getSpec(), arguments, name, true);
        return client.create(tag)
                .map(created -> payload(ContentPayloads.tag(created), "Created tag " + name));
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Tag.class, name)
                .switchIfEmpty(notFound("Tag", name))
                .flatMap(tag -> checkVersion(tag.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
                            apply(tag.getSpec(), arguments, name, false);
                            return client.update(tag);
                        })))
                .map(updated -> payload(ContentPayloads.tag(updated), "Updated tag " + name));
    }

    Mono<ToolPayload> delete(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = requiredLong(arguments, "expectedVersion");
        return client.fetch(Tag.class, name)
                .switchIfEmpty(notFound("Tag", name))
                .flatMap(tag -> checkVersion(tag.getMetadata().getVersion(), expectedVersion)
                        .then(client.delete(tag)))
                .map(tag -> payload(ContentPayloads.tag(tag), "Deleted tag " + name));
    }

    private BuiltInTool createTool() {
        return tool(
                CREATE,
                "Create Halo tag",
                "Create a post tag with display and visual metadata.",
                "创建标签",
                "创建文章标签，可设置描述、颜色和封面。",
                "TAG",
                objectSchema(tagProperties(false), List.of("name", "displayName")),
                ContentPayloads.tagSchema(),
                ToolSupport.CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        return tool(
                UPDATE,
                "Update Halo tag",
                "Update tag display and visual metadata.",
                "更新标签",
                "更新文章标签的名称、别名、描述、颜色和封面。",
                "TAG",
                objectSchema(tagProperties(true), List.of("name")),
                ContentPayloads.tagSchema(),
                ToolSupport.UPDATE,
                this::update);
    }

    private BuiltInTool deleteTool() {
        return tool(
                DELETE,
                "Delete Halo tag",
                "Delete a post tag after verifying its current resource version.",
                "删除标签",
                "校验资源版本后删除文章标签。",
                "TAG",
                objectSchema(
                        map(
                                "name", stringSchema("Tag metadata.name."),
                                "expectedVersion", described(
                                        integerSchema(),
                                        "Current metadata.version returned by a read or list call.")),
                        List.of("name", "expectedVersion")),
                ContentPayloads.tagSchema(),
                DESTRUCTIVE,
                this::delete);
    }

    private static Map<String, Object> tagProperties(boolean includeVersion) {
        var properties = map(
                "name", stringSchema(),
                "displayName", stringSchema(),
                "slug", stringSchema(),
                "description", nullableStringSchema(),
                "color", nullableStringSchema(),
                "cover", nullableStringSchema());
        if (includeVersion) {
            properties.put("expectedVersion", integerSchema());
        }
        return properties;
    }

    private static void apply(Tag.TagSpec spec, Map<String, Object> arguments, String name, boolean create) {
        if (create || arguments.containsKey("displayName")) {
            spec.setDisplayName(requiredString(arguments, "displayName"));
        }
        if (create || arguments.containsKey("slug")) {
            spec.setSlug(optionalString(arguments, "slug", name));
        }
        if (arguments.containsKey("description")) {
            spec.setDescription(nullableString(arguments, "description"));
        }
        if (arguments.containsKey("color")) {
            var color = nullableString(arguments, "color");
            if (color != null && !color.matches("^#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{6})$")) {
                throw new McpToolException(
                        "INVALID_ARGUMENT", "color must be a 3- or 6-digit hex color");
            }
            spec.setColor(color);
        }
        if (arguments.containsKey("cover")) {
            spec.setCover(nullableString(arguments, "cover"));
        }
    }
}
