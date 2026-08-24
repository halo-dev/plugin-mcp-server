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
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@Component
class CommentTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_comments";
    static final String SET_APPROVAL = "halo_set_comment_approval";
    static final String DELETE = "halo_delete_comment";
    static final String LIST_REPLIES = "halo_list_comment_replies";
    static final String SET_REPLY_APPROVAL = "halo_set_reply_approval";
    static final String DELETE_REPLY = "halo_delete_reply";

    private final ReactiveExtensionClient client;

    CommentTools(ReactiveExtensionClient client, McpAuthorization authorization) {
        super(authorization);
        this.client = client;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(
                listTool(),
                approvalTool(),
                deleteTool(),
                listRepliesTool(),
                replyApprovalTool(),
                deleteReplyTool());
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

    Mono<ToolPayload> setApproval(Map<String, Object> arguments) {
        return setApproved(arguments, requiredBoolean(arguments, "approved"));
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

    Mono<ToolPayload> listReplies(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var builder = ListOptions.builder().fieldQuery(ExtensionUtil.notDeleting());
        var commentName = optionalString(arguments, "commentName", null);
        if (StringUtils.hasText(commentName)) {
            builder.andQuery(equal("spec.commentName", resourceName(arguments, "commentName")));
        }
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
                Sort.by(desc("metadata.creationTimestamp"), desc("metadata.name")));
        return client.listBy(Reply.class, builder.build(), pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::reply),
                "Listed " + result.getItems().size() + " replies"));
    }

    Mono<ToolPayload> setReplyApproval(Map<String, Object> arguments) {
        return setReplyApproved(arguments, requiredBoolean(arguments, "approved"));
    }

    Mono<ToolPayload> deleteReply(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Reply.class, name)
                .switchIfEmpty(notFound("Reply", name))
                .flatMap(reply -> checkVersion(reply.getMetadata().getVersion(), expectedVersion)
                        .then(client.delete(reply)))
                .map(reply -> payload(ContentPayloads.reply(reply), "Deleted reply " + name));
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

    private Mono<ToolPayload> setReplyApproved(Map<String, Object> arguments, boolean approved) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Reply.class, name)
                .switchIfEmpty(notFound("Reply", name))
                .flatMap(reply -> checkVersion(reply.getMetadata().getVersion(), expectedVersion)
                        .then(Mono.defer(() -> {
                            reply.getSpec().setApproved(approved);
                            reply.getSpec().setApprovedTime(approved ? Instant.now() : null);
                            return client.update(reply);
                        })))
                .map(reply -> payload(
                        ContentPayloads.reply(reply),
                        (approved ? "Approved reply " : "Unapproved reply ") + name));
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
                pageOutputSchema(ContentPayloads.commentSchema()),
                READ_ONLY,
                this::list);
    }

    private BuiltInTool deleteTool() {
        return tool(
                DELETE,
                "Delete Halo comment",
                "Delete a comment and let Halo clean up its replies and counters.",
                "删除评论",
                "删除评论，并由 Halo 清理关联回复和评论计数。",
                "COMMENT",
                objectSchema(
                        map("name", stringSchema(), "expectedVersion", integerSchema()),
                        List.of("name")),
                ContentPayloads.commentSchema(),
                DESTRUCTIVE,
                this::delete);
    }

    private BuiltInTool approvalTool() {
        return tool(
                SET_APPROVAL,
                "Set Halo comment approval",
                "Approve a comment or return it to pending moderation.",
                "修改评论审核状态",
                "设置评论为审核通过或待审核状态，并同步维护审核时间。",
                "COMMENT",
                objectSchema(
                        map(
                                "name", stringSchema(),
                                "approved", booleanSchema(),
                                "expectedVersion", integerSchema()),
                        List.of("name", "approved")),
                ContentPayloads.commentSchema(),
                UPDATE,
                this::setApproval);
    }

    private BuiltInTool listRepliesTool() {
        return tool(
                LIST_REPLIES,
                "List Halo comment replies",
                "List comment replies with parent, moderation, and keyword filters.",
                "查询评论回复",
                "分页查询评论回复，可按所属评论、审核状态或回复内容筛选。",
                "COMMENT",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE),
                                "commentName", stringSchema(),
                                "approved", booleanSchema(),
                                "keyword", stringSchema()),
                        List.of()),
                pageOutputSchema(ContentPayloads.replySchema()),
                READ_ONLY,
                this::listReplies);
    }

    private BuiltInTool deleteReplyTool() {
        return tool(
                DELETE_REPLY,
                "Delete Halo reply",
                "Delete a comment reply through Halo's extension lifecycle.",
                "删除回复",
                "删除评论回复，并由 Halo 更新关联计数。",
                "COMMENT",
                objectSchema(
                        map("name", stringSchema(), "expectedVersion", integerSchema()),
                        List.of("name")),
                ContentPayloads.replySchema(),
                DESTRUCTIVE,
                this::deleteReply);
    }

    private BuiltInTool replyApprovalTool() {
        return tool(
                SET_REPLY_APPROVAL,
                "Set Halo reply approval",
                "Approve a comment reply or return it to pending moderation.",
                "修改回复审核状态",
                "设置评论回复为审核通过或待审核状态，并同步维护审核时间。",
                "COMMENT",
                objectSchema(
                        map(
                                "name", stringSchema(),
                                "approved", booleanSchema(),
                                "expectedVersion", integerSchema()),
                        List.of("name", "approved")),
                ContentPayloads.replySchema(),
                UPDATE,
                this::setReplyApproval);
    }
}
