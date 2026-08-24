package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.or;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class CategoryTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_categories";
    static final String CREATE = "halo_create_category";
    static final String UPDATE = "halo_update_category";

    private final ReactiveExtensionClient client;
    private final CategoryParentMutationCoordinator parentMutationCoordinator;

    CategoryTools(
            ReactiveExtensionClient client,
            McpAuthorization authorization,
            CategoryParentMutationCoordinator parentMutationCoordinator) {
        super(authorization);
        this.client = client;
        this.parentMutationCoordinator = parentMutationCoordinator;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(tool(
                LIST,
                "List Halo categories",
                "List post categories with hierarchy and post counters.",
                "查询分类",
                "分页查询文章分类，返回父级关系、优先级和文章数量。",
                "CATEGORY",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE),
                                "keyword", stringSchema(),
                                "parent", stringSchema()),
                        List.of()),
                pageOutputSchema(ContentPayloads.categorySchema()),
                READ_ONLY,
                this::list), createTool(), updateTool());
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var builder = ListOptions.builder().fieldQuery(ExtensionUtil.notDeleting());
        var keyword = optionalString(arguments, "keyword", null);
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(or(contains("spec.displayName", keyword), contains("spec.slug", keyword)));
        }
        var parent = optionalString(arguments, "parent", null);
        if (StringUtils.hasText(parent)) {
            builder.andQuery(equal("spec.parent", parent));
        }
        var pageable = PageRequestImpl.of(
                page,
                size,
                Sort.by(asc("spec.priority"), asc("metadata.creationTimestamp"), asc("metadata.name")));
        return client.listBy(Category.class, builder.build(), pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::category),
                "Listed " + result.getItems().size() + " categories"));
    }

    Mono<ToolPayload> create(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var parent = parent(arguments);
        Supplier<Mono<ToolPayload>> create = () -> validateParent(name, parent)
                .then(Mono.defer(() -> {
                    var category = new Category();
                    category.setMetadata(metadata(name));
                    category.setSpec(new Category.CategorySpec());
                    apply(category.getSpec(), arguments, name, true);
                    return client.create(category);
                }))
                .map(category -> payload(ContentPayloads.category(category), "Created category " + name));
        return parent == null ? create.get() : parentMutationCoordinator.serialize(create);
    }

    Mono<ToolPayload> update(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        var parent = arguments.containsKey("parent") ? parent(arguments) : null;
        Supplier<Mono<ToolPayload>> update = () -> client.fetch(Category.class, name)
                .switchIfEmpty(notFound("Category", name))
                .flatMap(category -> checkVersion(category.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
                            apply(category.getSpec(), arguments, name, false);
                            return client.update(category);
                        })))
                .map(category -> payload(ContentPayloads.category(category), "Updated category " + name));
        if (!arguments.containsKey("parent")) {
            return update.get();
        }
        return parentMutationCoordinator.serialize(
                () -> validateParent(name, parent).then(Mono.defer(update)));
    }

    private BuiltInTool createTool() {
        return tool(
                CREATE,
                "Create Halo category",
                "Create a post category with display, hierarchy, and theme metadata.",
                "创建分类",
                "创建文章分类，可设置父分类、封面、模板和列表行为。",
                "CATEGORY",
                objectSchema(categoryProperties(true), List.of("name", "displayName")),
                ContentPayloads.categorySchema(),
                ToolSupport.CREATE,
                this::create);
    }

    private BuiltInTool updateTool() {
        var properties = categoryProperties(false);
        properties.put("expectedVersion", integerSchema());
        return tool(
                UPDATE,
                "Update Halo category",
                "Update category display, hierarchy, and theme metadata.",
                "更新分类",
                "更新文章分类的名称、层级、封面、模板和列表行为。",
                "CATEGORY",
                objectSchema(properties, List.of("name")),
                ContentPayloads.categorySchema(),
                ToolSupport.UPDATE,
                this::update);
    }

    private static Map<String, Object> categoryProperties(boolean create) {
        return map(
                "name", stringSchema(),
                "displayName", stringSchema(),
                "slug", stringSchema(),
                "description", nullableStringSchema(),
                "cover", nullableStringSchema(),
                "template", nullableStringSchema(),
                "postTemplate", nullableStringSchema(),
                "priority", create ? nonNegativeIntegerSchema(0) : nonNegativeIntegerSchema(),
                "parent", nullableStringSchema(),
                "preventParentPostCascadeQuery", create ? booleanSchema(false) : booleanSchema(),
                "hideFromList", create ? booleanSchema(false) : booleanSchema());
    }

    private static void apply(
            Category.CategorySpec spec, Map<String, Object> arguments, String name, boolean create) {
        if (create || arguments.containsKey("displayName")) {
            spec.setDisplayName(requiredString(arguments, "displayName"));
        }
        if (create || arguments.containsKey("slug")) {
            spec.setSlug(optionalString(arguments, "slug", name));
        }
        if (arguments.containsKey("description")) {
            spec.setDescription(nullableString(arguments, "description"));
        }
        if (arguments.containsKey("cover")) {
            spec.setCover(nullableString(arguments, "cover"));
        }
        if (arguments.containsKey("template")) {
            spec.setTemplate(nullableString(arguments, "template"));
        }
        if (arguments.containsKey("postTemplate")) {
            spec.setPostTemplate(nullableString(arguments, "postTemplate"));
        }
        if (create || arguments.containsKey("priority")) {
            spec.setPriority(optionalNonNegativeInt(arguments, "priority", 0));
        }
        if (arguments.containsKey("parent")) {
            spec.setParent(parent(arguments));
        }
        if (create || arguments.containsKey("preventParentPostCascadeQuery")) {
            spec.setPreventParentPostCascadeQuery(
                    optionalBoolean(arguments, "preventParentPostCascadeQuery", false));
        }
        if (create || arguments.containsKey("hideFromList")) {
            spec.setHideFromList(optionalBoolean(arguments, "hideFromList", false));
        }
    }

    private static String parent(Map<String, Object> arguments) {
        var parent = nullableString(arguments, "parent");
        if (parent == null) {
            return null;
        }
        return resourceName(Map.of("parent", parent), "parent");
    }

    private Mono<Void> validateParent(String categoryName, String parentName) {
        return validateParent(categoryName, parentName, new HashSet<>());
    }

    private Mono<Void> validateParent(String categoryName, String parentName, HashSet<String> visited) {
        if (parentName == null) {
            return Mono.empty();
        }
        if (categoryName.equals(parentName) || !visited.add(parentName)) {
            return Mono.error(new McpToolException(
                    "INVALID_ARGUMENT", "parent would create a category cycle"));
        }
        return client.fetch(Category.class, parentName)
                .switchIfEmpty(Mono.error(new McpToolException(
                        "INVALID_ARGUMENT", "Parent category not found: " + parentName)))
                .flatMap(parent -> validateParent(categoryName, parent.getSpec().getParent(), visited));
    }
}
