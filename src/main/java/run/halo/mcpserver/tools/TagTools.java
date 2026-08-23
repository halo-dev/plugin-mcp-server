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

@Component
class TagTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_tags";

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
                this::list));
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
}
