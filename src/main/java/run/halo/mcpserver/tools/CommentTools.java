package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@Component
class CommentTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_comments";
    static final String APPROVE = "halo_approve_comment";
    static final String UNAPPROVE = "halo_unapprove_comment";
    static final String DELETE = "halo_delete_comment";

    private final ReactiveExtensionClient client;

    CommentTools(ReactiveExtensionClient client, McpAuthorization authorization) {
        super(authorization);
        this.client = client;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(
                listTool(),
                moderationTool(
                        APPROVE,
                        "Approve Halo comment",
                        "Approve a pending comment and record the approval time.",
                        "审核通过评论",
                        "将待审核评论设为已通过并记录审核时间。",
                        false,
                        this::approve),
                moderationTool(
                        UNAPPROVE,
                        "Unapprove Halo comment",
                        "Return an approved comment to pending moderation.",
                        "取消审核评论",
                        "取消评论的已通过状态，使其重新进入待审核状态。",
                        false,
                        this::unapprove),
                moderationTool(
                        DELETE,
                        "Delete Halo comment",
                        "Delete a comment and let Halo clean up its replies and counters.",
                        "删除评论",
                        "删除评论，并由 Halo 清理关联回复和评论计数。",
                        true,
                        this::delete));
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var builder = ListOptions.builder().fieldQuery(ExtensionUtil.notDeleting());
        var approved = optionalBoolean(arguments, "approved");
        if (approved != null) {
            builder.andQuery(equal("spec.approved", approved));
        }
        var keyword = optionalString(arguments, "keyword", null);
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(contains("spec.raw", keyword));
        }
        var pageable = PageRequestImpl.of(
                page,
                size,
                Sort.by(
                        desc("metadata.creationTimestamp"),
                        desc("status.lastReplyTime"),
                        desc("metadata.name")));
        return client.listBy(Comment.class, builder.build(), pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::comment),
                "Listed " + result.getItems().size() + " comments"));
    }

    Mono<ToolPayload> approve(Map<String, Object> arguments) {
        return setApproved(arguments, true);
    }

    Mono<ToolPayload> unapprove(Map<String, Object> arguments) {
        return setApproved(arguments, false);
    }

    Mono<ToolPayload> delete(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Comment.class, name)
                .switchIfEmpty(notFound("Comment", name))
                .flatMap(comment -> checkVersion(comment.getMetadata().getVersion(), expectedVersion)
                        .then(client.delete(comment)))
                .map(comment -> payload(ContentPayloads.comment(comment), "Deleted comment " + name));
    }

    private Mono<ToolPayload> setApproved(Map<String, Object> arguments, boolean approved) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Comment.class, name)
                .switchIfEmpty(notFound("Comment", name))
                .flatMap(comment -> checkVersion(comment.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
                            comment.getSpec().setApproved(approved);
                            comment.getSpec().setApprovedTime(approved ? Instant.now() : null);
                            return client.update(comment);
                        })))
                .map(comment -> payload(
                        ContentPayloads.comment(comment),
                        (approved ? "Approved comment " : "Unapproved comment ") + name));
    }

    private BuiltInTool listTool() {
        return tool(
                LIST,
                "List Halo comments",
                "List comments with moderation and keyword filters.",
                "查询评论",
                "分页查询评论，可按审核状态或评论内容筛选。",
                "COMMENT",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE),
                                "approved", booleanSchema(),
                                "keyword", stringSchema()),
                        List.of()),
                pageOutputSchema(),
                READ_ONLY,
                this::list);
    }

    private BuiltInTool moderationTool(
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
                "COMMENT",
                objectSchema(
                        map("name", stringSchema(), "expectedVersion", integerSchema()),
                        List.of("name")),
                permissiveObjectSchema(),
                destructive ? DESTRUCTIVE : UPDATE,
                handler);
    }
}
