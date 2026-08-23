package run.halo.mcpserver.tools;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchOption;
import run.halo.app.search.SearchResult;
import run.halo.app.search.SearchService;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class ContentSearchTools extends ToolSupport implements ToolGroup {

    static final String SEARCH_CONTENT = "halo_search_content";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final String POST_TYPE = "post.content.halo.run";
    private static final String PAGE_TYPE = "singlepage.content.halo.run";

    private final SearchService searchService;

    ContentSearchTools(SearchService searchService, McpAuthorization authorization) {
        super(authorization);
        this.searchService = searchService;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(tool(
                SEARCH_CONTENT,
                "Search Halo content",
                "Search posts and single pages. Drafts are included unless published is specified.",
                "搜索内容",
                "搜索文章和独立页面，可按发布状态和回收站状态筛选。",
                "CONTENT_SEARCH",
                objectSchema(
                        map(
                                "query", stringSchema("Search keyword"),
                                "types", arraySchema(Map.of(
                                        "type", "string",
                                        "enum", List.of("POST", "SINGLE_PAGE"))),
                                "limit", integerSchema(1, MAX_LIMIT, DEFAULT_LIMIT),
                                "published", booleanSchema(),
                                "recycled", booleanSchema(false)),
                        List.of("query")),
                searchOutputSchema(),
                READ_ONLY,
                this::search));
    }

    Mono<ToolPayload> search(Map<String, Object> arguments) {
        var option = new SearchOption();
        option.setKeyword(requiredString(arguments, "query"));
        option.setLimit(boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT));
        option.setFilterPublished(optionalBoolean(arguments, "published"));
        option.setFilterRecycled(optionalBoolean(arguments, "recycled", false));
        option.setIncludeTypes(contentTypes(arguments.get("types")));
        return searchService
                .search(option)
                .map(this::result)
                .onErrorMap(error -> error instanceof McpToolException
                        ? error
                        : new McpToolException(
                                "SEARCH_UNAVAILABLE", "Halo search is currently unavailable", error));
    }

    private ToolPayload result(SearchResult result) {
        var hits = Optional.ofNullable(result.getHits()).orElseGet(List::of);
        var items = hits.stream().map(ContentSearchTools::summary).toList();
        var total = Optional.ofNullable(result.getTotal()).orElse((long) items.size());
        return payload(
                map(
                        "items", items,
                        "total", total,
                        "limit", result.getLimit(),
                        "processingTimeMillis", result.getProcessingTimeMillis()),
                "Found " + items.size() + " content items");
    }

    private static Map<String, Object> summary(HaloDocument document) {
        return map(
                "type", POST_TYPE.equals(document.getType()) ? "POST" : "SINGLE_PAGE",
                "name", document.getMetadataName(),
                "title", document.getTitle(),
                "excerpt", document.getDescription(),
                "published", document.isPublished(),
                "recycled", document.isRecycled(),
                "exposed", document.isExposed(),
                "owner", document.getOwnerName(),
                "categories", document.getCategories() == null ? List.of() : document.getCategories(),
                "tags", document.getTags() == null ? List.of() : document.getTags(),
                "permalink", document.getPermalink(),
                "creationTimestamp", instant(document.getCreationTimestamp()),
                "updateTimestamp", instant(document.getUpdateTimestamp()));
    }

    private static Map<String, Object> searchOutputSchema() {
        var itemSchema = objectSchema(
                map(
                        "type",
                                described(
                                        Map.of("type", "string", "enum", List.of("POST", "SINGLE_PAGE")),
                                        "Halo content type represented by this search result."),
                        "name",
                                described(
                                        HaloSchemaProperties.property(HaloDocument.class, "metadataName"),
                                        "Metadata name of the matching Post or SinglePage."),
                        "title", HaloSchemaProperties.property(HaloDocument.class, "title"),
                        "excerpt",
                                described(
                                        HaloSchemaProperties.property(HaloDocument.class, "description"),
                                        "Search excerpt or description of the matching content."),
                        "published", HaloSchemaProperties.property(HaloDocument.class, "published"),
                        "recycled", HaloSchemaProperties.property(HaloDocument.class, "recycled"),
                        "exposed", HaloSchemaProperties.property(HaloDocument.class, "exposed"),
                        "owner",
                                described(
                                        HaloSchemaProperties.property(HaloDocument.class, "ownerName"),
                                        "User metadata.name of the content owner."),
                        "categories", HaloSchemaProperties.property(HaloDocument.class, "categories"),
                        "tags", HaloSchemaProperties.property(HaloDocument.class, "tags"),
                        "permalink", HaloSchemaProperties.property(HaloDocument.class, "permalink"),
                        "creationTimestamp",
                                HaloSchemaProperties.property(HaloDocument.class, "creationTimestamp"),
                        "updateTimestamp", HaloSchemaProperties.property(HaloDocument.class, "updateTimestamp")),
                List.of("type", "published", "recycled", "exposed", "categories", "tags"));
        return objectSchema(
                map(
                        "items", described(outputArraySchema(itemSchema), "Matching content items."),
                        "total", described(outputIntegerSchema(), "Total number of matching content items."),
                        "limit", described(outputIntegerSchema(), "Maximum number of returned items."),
                        "processingTimeMillis",
                                described(outputIntegerSchema(), "Search processing time in milliseconds.")),
                List.of("items", "total", "limit", "processingTimeMillis"));
    }

    private static List<String> contentTypes(Object value) {
        if (value == null) {
            return List.of(POST_TYPE, PAGE_TYPE);
        }
        if (!(value instanceof List<?> list)) {
            throw new McpToolException("INVALID_ARGUMENT", "types must be an array");
        }
        var types = list.stream().map(item -> {
            if (!(item instanceof String text)) {
                throw new McpToolException("INVALID_ARGUMENT", "types must contain strings");
            }
            return switch (text.toUpperCase(java.util.Locale.ROOT)) {
                case "POST" -> POST_TYPE;
                case "SINGLE_PAGE" -> PAGE_TYPE;
                default -> throw new McpToolException("INVALID_ARGUMENT", "types contains an unsupported value");
            };
        }).distinct().toList();
        if (types.isEmpty()) {
            throw new McpToolException("INVALID_ARGUMENT", "types must not be empty");
        }
        return types;
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
