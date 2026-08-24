package run.halo.mcpserver.tools;

import static run.halo.mcpserver.tools.ToolSupport.map;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.Ref;

final class ContentPayloads {

    private ContentPayloads() {}

    static Map<String, Object> postSchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "title", halo(Post.PostSpec.class, "title"),
                        "slug", halo(Post.PostSpec.class, "slug"),
                        "excerpt", halo(Post.PostStatus.class, "excerpt"),
                        "excerptRaw", halo(Post.Excerpt.class, "raw"),
                        "autoGenerateExcerpt", halo(Post.Excerpt.class, "autoGenerate"),
                        "cover", halo(Post.PostSpec.class, "cover"),
                        "template", halo(Post.PostSpec.class, "template"),
                        "pinned", halo(Post.PostSpec.class, "pinned"),
                        "priority", halo(Post.PostSpec.class, "priority"),
                        "publishTime", halo(Post.PostSpec.class, "publishTime"),
                        "allowComment", halo(Post.PostSpec.class, "allowComment"),
                        "published",
                                ToolSupport.described(
                                        ToolSupport.booleanSchema(), "Whether the post is currently published."),
                        "publishRequested",
                                describedHalo(
                                        Post.PostSpec.class,
                                        "publish",
                                        "Whether publication is requested for the post."),
                        "recycled",
                                describedHalo(
                                        Post.PostSpec.class,
                                        "deleted",
                                        "Whether the post is currently in the recycle bin."),
                        "visible", halo(Post.PostSpec.class, "visible"),
                        "owner", halo(Post.PostSpec.class, "owner"),
                        "categories", halo(Post.PostSpec.class, "categories"),
                        "tags", halo(Post.PostSpec.class, "tags"),
                        "permalink", halo(Post.PostStatus.class, "permalink"),
                        "headSnapshot", halo(Post.PostSpec.class, "headSnapshot"),
                        "releaseSnapshot", halo(Post.PostSpec.class, "releaseSnapshot"),
                        "baseSnapshot", halo(Post.PostSpec.class, "baseSnapshot"),
                        "version", halo(Metadata.class, "version"),
                        "creationTimestamp", halo(Metadata.class, "creationTimestamp"),
                        "updateTimestamp",
                                describedHalo(
                                        Post.PostStatus.class,
                                        "lastModifyTime",
                                        "Last modification time of the released post content.")),
                List.of("published", "publishRequested", "recycled", "categories", "tags"));
    }

    static Map<String, Object> singlePageSchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "title", halo(SinglePage.SinglePageSpec.class, "title"),
                        "slug", halo(SinglePage.SinglePageSpec.class, "slug"),
                        "excerpt", halo(Post.PostStatus.class, "excerpt"),
                        "published",
                                ToolSupport.described(
                                        ToolSupport.booleanSchema(),
                                        "Whether the single page is currently published."),
                        "publishRequested",
                                describedHalo(
                                        SinglePage.SinglePageSpec.class,
                                        "publish",
                                        "Whether publication is requested for the single page."),
                        "recycled",
                                describedHalo(
                                        SinglePage.SinglePageSpec.class,
                                        "deleted",
                                        "Whether the single page is currently in the recycle bin."),
                        "visible", halo(SinglePage.SinglePageSpec.class, "visible"),
                        "owner", halo(SinglePage.SinglePageSpec.class, "owner"),
                        "permalink", halo(Post.PostStatus.class, "permalink"),
                        "headSnapshot", halo(SinglePage.SinglePageSpec.class, "headSnapshot"),
                        "releaseSnapshot", halo(SinglePage.SinglePageSpec.class, "releaseSnapshot"),
                        "baseSnapshot", halo(SinglePage.SinglePageSpec.class, "baseSnapshot"),
                        "version", halo(Metadata.class, "version"),
                        "creationTimestamp", halo(Metadata.class, "creationTimestamp"),
                        "updateTimestamp",
                                describedHalo(
                                        Post.PostStatus.class,
                                        "lastModifyTime",
                                        "Last modification time of the released single page content.")),
                List.of("published", "publishRequested", "recycled"));
    }

    static Map<String, Object> categorySchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "displayName", halo(Category.CategorySpec.class, "displayName"),
                        "slug", halo(Category.CategorySpec.class, "slug"),
                        "description", halo(Category.CategorySpec.class, "description"),
                        "cover", halo(Category.CategorySpec.class, "cover"),
                        "template", halo(Category.CategorySpec.class, "template"),
                        "postTemplate", halo(Category.CategorySpec.class, "postTemplate"),
                        "parent", halo(Category.CategorySpec.class, "parent"),
                        "priority", halo(Category.CategorySpec.class, "priority"),
                        "hideFromList", halo(Category.CategorySpec.class, "hideFromList"),
                        "preventParentPostCascadeQuery",
                                halo(Category.CategorySpec.class, "preventParentPostCascadeQuery"),
                        "permalink", halo(Category.CategoryStatus.class, "permalink"),
                        "postCount", halo(Category.CategoryStatus.class, "postCount"),
                        "visiblePostCount", halo(Category.CategoryStatus.class, "visiblePostCount"),
                        "version", halo(Metadata.class, "version")),
                List.of("hideFromList"));
    }

    static Map<String, Object> tagSchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "displayName", halo(Tag.TagSpec.class, "displayName"),
                        "slug", halo(Tag.TagSpec.class, "slug"),
                        "description", halo(Tag.TagSpec.class, "description"),
                        "color", halo(Tag.TagSpec.class, "color"),
                        "cover", halo(Tag.TagSpec.class, "cover"),
                        "permalink", halo(Tag.TagStatus.class, "permalink"),
                        "postCount", halo(Tag.TagStatus.class, "postCount"),
                        "visiblePostCount", halo(Tag.TagStatus.class, "visiblePostCount"),
                        "version", halo(Metadata.class, "version")),
                List.of());
    }

    static Map<String, Object> commentSchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "raw", halo(Comment.BaseCommentSpec.class, "raw"),
                        "content", halo(Comment.BaseCommentSpec.class, "content"),
                        "approved", halo(Comment.BaseCommentSpec.class, "approved"),
                        "approvedTime", halo(Comment.BaseCommentSpec.class, "approvedTime"),
                        "hidden", halo(Comment.BaseCommentSpec.class, "hidden"),
                        "top", halo(Comment.BaseCommentSpec.class, "top"),
                        "ownerKind",
                                describedHalo(
                                        Comment.CommentOwner.class,
                                        "kind",
                                        "Identity kind of the comment owner. Built-in values are User and Email."),
                        "ownerName",
                                describedHalo(
                                        Comment.CommentOwner.class,
                                        "name",
                                        "Comment owner identifier; User owners use User metadata.name."),
                        "ownerDisplayName",
                                describedHalo(
                                        Comment.CommentOwner.class,
                                        "displayName",
                                        "Display name shown for the comment owner."),
                        "subjectGroup",
                                describedHalo(Ref.class, "group", "Extension group of the comment subject."),
                        "subjectKind",
                                describedHalo(Ref.class, "kind", "Extension kind of the comment subject."),
                        "subjectName",
                                describedHalo(Ref.class, "name", "Extension metadata.name of the comment subject."),
                        "creationTime",
                                describedHalo(
                                        Comment.BaseCommentSpec.class,
                                        "creationTime",
                                        "Comment creation time, falling back to metadata.creationTimestamp."),
                        "version", halo(Metadata.class, "version")),
                List.of("approved", "hidden", "top"));
    }

    static Map<String, Object> replySchema() {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("name", halo(Metadata.class, "name"));
        properties.put("raw", halo(Comment.BaseCommentSpec.class, "raw"));
        properties.put("content", halo(Comment.BaseCommentSpec.class, "content"));
        properties.put("approved", halo(Comment.BaseCommentSpec.class, "approved"));
        properties.put("approvedTime", halo(Comment.BaseCommentSpec.class, "approvedTime"));
        properties.put("hidden", halo(Comment.BaseCommentSpec.class, "hidden"));
        properties.put("top", halo(Comment.BaseCommentSpec.class, "top"));
        properties.put(
                "ownerKind",
                describedHalo(
                        Comment.CommentOwner.class,
                        "kind",
                        "Identity kind of the reply owner. Built-in values are User and Email."));
        properties.put(
                "ownerName",
                describedHalo(
                        Comment.CommentOwner.class,
                        "name",
                        "Reply owner identifier; User owners use User metadata.name."));
        properties.put(
                "ownerDisplayName",
                describedHalo(
                        Comment.CommentOwner.class,
                        "displayName",
                        "Display name shown for the reply owner."));
        properties.put(
                "creationTime",
                describedHalo(
                        Comment.BaseCommentSpec.class,
                        "creationTime",
                        "Reply creation time, falling back to metadata.creationTimestamp."));
        properties.put("version", halo(Metadata.class, "version"));
        properties.put("commentName", halo(Reply.ReplySpec.class, "commentName"));
        properties.put("quoteReply", halo(Reply.ReplySpec.class, "quoteReply"));
        return ToolSupport.objectSchema(properties, List.of("approved", "hidden", "top"));
    }

    static Map<String, Object> attachmentSchema() {
        return ToolSupport.objectSchema(
                map(
                        "name", halo(Metadata.class, "name"),
                        "displayName", halo(Attachment.AttachmentSpec.class, "displayName"),
                        "groupName", halo(Attachment.AttachmentSpec.class, "groupName"),
                        "policyName", halo(Attachment.AttachmentSpec.class, "policyName"),
                        "ownerName", halo(Attachment.AttachmentSpec.class, "ownerName"),
                        "mediaType", halo(Attachment.AttachmentSpec.class, "mediaType"),
                        "size", halo(Attachment.AttachmentSpec.class, "size"),
                        "permalink", halo(Attachment.AttachmentStatus.class, "permalink"),
                        "thumbnails", halo(Attachment.AttachmentStatus.class, "thumbnails"),
                        "version", halo(Metadata.class, "version")),
                List.of());
    }

    private static Map<String, Object> halo(Class<?> owner, String name) {
        return HaloSchemaProperties.property(owner, name);
    }

    private static Map<String, Object> describedHalo(
            Class<?> owner, String name, String description) {
        return ToolSupport.described(halo(owner, name), description);
    }

    static Map<String, Object> post(Post post) {
        var spec = post.getSpec();
        var status = post.getStatus();
        return map(
                "name", name(post.getMetadata()),
                "title", spec == null ? null : spec.getTitle(),
                "slug", spec == null ? null : spec.getSlug(),
                "excerpt", status == null ? null : status.getExcerpt(),
                "excerptRaw", spec == null || spec.getExcerpt() == null
                        ? null
                        : spec.getExcerpt().getRaw(),
                "autoGenerateExcerpt", spec == null
                        || spec.getExcerpt() == null
                        || Boolean.TRUE.equals(spec.getExcerpt().getAutoGenerate()),
                "cover", spec == null ? null : spec.getCover(),
                "template", spec == null ? null : spec.getTemplate(),
                "pinned", spec != null && Boolean.TRUE.equals(spec.getPinned()),
                "priority", spec == null ? null : spec.getPriority(),
                "publishTime", spec == null ? null : instant(spec.getPublishTime()),
                "allowComment", spec != null && Boolean.TRUE.equals(spec.getAllowComment()),
                "published", published(post.getMetadata(), Post.PUBLISHED_LABEL),
                "publishRequested", spec != null && Boolean.TRUE.equals(spec.getPublish()),
                "recycled", spec != null && Boolean.TRUE.equals(spec.getDeleted()),
                "visible", spec == null || spec.getVisible() == null ? null : spec.getVisible().name(),
                "owner", spec == null ? null : spec.getOwner(),
                "categories", spec == null || spec.getCategories() == null ? List.of() : spec.getCategories(),
                "tags", spec == null || spec.getTags() == null ? List.of() : spec.getTags(),
                "permalink", status == null ? null : status.getPermalink(),
                "headSnapshot", spec == null ? null : spec.getHeadSnapshot(),
                "releaseSnapshot", spec == null ? null : spec.getReleaseSnapshot(),
                "baseSnapshot", spec == null ? null : spec.getBaseSnapshot(),
                "version", post.getMetadata() == null ? null : post.getMetadata().getVersion(),
                "creationTimestamp", instant(post.getMetadata() == null
                        ? null
                        : post.getMetadata().getCreationTimestamp()),
                "updateTimestamp", status == null ? null : instant(status.getLastModifyTime()));
    }

    static Map<String, Object> singlePage(SinglePage page) {
        var spec = page.getSpec();
        var status = page.getStatus();
        return map(
                "name", name(page.getMetadata()),
                "title", spec == null ? null : spec.getTitle(),
                "slug", spec == null ? null : spec.getSlug(),
                "excerpt", status == null ? null : status.getExcerpt(),
                "published", published(page.getMetadata(), SinglePage.PUBLISHED_LABEL),
                "publishRequested", spec != null && Boolean.TRUE.equals(spec.getPublish()),
                "recycled", spec != null && Boolean.TRUE.equals(spec.getDeleted()),
                "visible", spec == null || spec.getVisible() == null ? null : spec.getVisible().name(),
                "owner", spec == null ? null : spec.getOwner(),
                "permalink", status == null ? null : status.getPermalink(),
                "headSnapshot", spec == null ? null : spec.getHeadSnapshot(),
                "releaseSnapshot", spec == null ? null : spec.getReleaseSnapshot(),
                "baseSnapshot", spec == null ? null : spec.getBaseSnapshot(),
                "version", page.getMetadata() == null ? null : page.getMetadata().getVersion(),
                "creationTimestamp", instant(page.getMetadata() == null
                        ? null
                        : page.getMetadata().getCreationTimestamp()),
                "updateTimestamp", status == null ? null : instant(status.getLastModifyTime()));
    }

    static Map<String, Object> category(Category category) {
        var spec = category.getSpec();
        var status = category.getStatus();
        return map(
                "name", name(category.getMetadata()),
                "displayName", spec == null ? null : spec.getDisplayName(),
                "slug", spec == null ? null : spec.getSlug(),
                "description", spec == null ? null : spec.getDescription(),
                "cover", spec == null ? null : spec.getCover(),
                "template", spec == null ? null : spec.getTemplate(),
                "postTemplate", spec == null ? null : spec.getPostTemplate(),
                "parent", spec == null ? null : spec.getParent(),
                "priority", spec == null ? null : spec.getPriority(),
                "hideFromList", spec != null && spec.isHideFromList(),
                "preventParentPostCascadeQuery",
                        spec != null && spec.isPreventParentPostCascadeQuery(),
                "permalink", status == null ? null : status.getPermalink(),
                "postCount", status == null ? null : status.getPostCount(),
                "visiblePostCount", status == null ? null : status.getVisiblePostCount(),
                "version", category.getMetadata() == null ? null : category.getMetadata().getVersion());
    }

    static Map<String, Object> tag(Tag tag) {
        var spec = tag.getSpec();
        var status = tag.getStatus();
        return map(
                "name", name(tag.getMetadata()),
                "displayName", spec == null ? null : spec.getDisplayName(),
                "slug", spec == null ? null : spec.getSlug(),
                "description", spec == null ? null : spec.getDescription(),
                "color", spec == null ? null : spec.getColor(),
                "cover", spec == null ? null : spec.getCover(),
                "permalink", status == null ? null : status.getPermalink(),
                "postCount", status == null ? null : status.getPostCount(),
                "visiblePostCount", status == null ? null : status.getVisiblePostCount(),
                "version", tag.getMetadata() == null ? null : tag.getMetadata().getVersion());
    }

    static Map<String, Object> comment(Comment comment) {
        var spec = comment.getSpec();
        var owner = spec == null ? null : spec.getOwner();
        var subject = spec == null ? null : spec.getSubjectRef();
        return map(
                "name", name(comment.getMetadata()),
                "raw", spec == null ? null : spec.getRaw(),
                "content", spec == null ? null : spec.getContent(),
                "approved", spec != null && Boolean.TRUE.equals(spec.getApproved()),
                "approvedTime", spec == null ? null : instant(spec.getApprovedTime()),
                "hidden", spec != null && Boolean.TRUE.equals(spec.getHidden()),
                "top", spec != null && Boolean.TRUE.equals(spec.getTop()),
                "ownerKind", owner == null ? null : owner.getKind(),
                "ownerName", owner == null ? null : owner.getName(),
                "ownerDisplayName", owner == null ? null : owner.getDisplayName(),
                "subjectGroup", subject == null ? null : subject.getGroup(),
                "subjectKind", subject == null ? null : subject.getKind(),
                "subjectName", subject == null ? null : subject.getName(),
                "creationTime", spec == null || spec.getCreationTime() == null
                        ? instant(comment.getMetadata() == null
                                ? null
                                : comment.getMetadata().getCreationTimestamp())
                        : instant(spec.getCreationTime()),
                "version", comment.getMetadata() == null ? null : comment.getMetadata().getVersion());
    }

    static Map<String, Object> reply(Reply reply) {
        var spec = reply.getSpec();
        var owner = spec == null ? null : spec.getOwner();
        return map(
                "name", name(reply.getMetadata()),
                "raw", spec == null ? null : spec.getRaw(),
                "content", spec == null ? null : spec.getContent(),
                "approved", spec != null && Boolean.TRUE.equals(spec.getApproved()),
                "approvedTime", spec == null ? null : instant(spec.getApprovedTime()),
                "hidden", spec != null && Boolean.TRUE.equals(spec.getHidden()),
                "top", spec != null && Boolean.TRUE.equals(spec.getTop()),
                "ownerKind", owner == null ? null : owner.getKind(),
                "ownerName", owner == null ? null : owner.getName(),
                "ownerDisplayName", owner == null ? null : owner.getDisplayName(),
                "creationTime", spec == null || spec.getCreationTime() == null
                        ? instant(reply.getMetadata() == null
                                ? null
                                : reply.getMetadata().getCreationTimestamp())
                        : instant(spec.getCreationTime()),
                "version", reply.getMetadata() == null ? null : reply.getMetadata().getVersion(),
                "commentName", spec == null ? null : spec.getCommentName(),
                "quoteReply", spec == null ? null : spec.getQuoteReply());
    }

    static Map<String, Object> attachment(Attachment attachment) {
        var spec = attachment.getSpec();
        var status = attachment.getStatus();
        return map(
                "name", name(attachment.getMetadata()),
                "displayName", spec == null ? null : spec.getDisplayName(),
                "groupName", spec == null ? null : spec.getGroupName(),
                "policyName", spec == null ? null : spec.getPolicyName(),
                "ownerName", spec == null ? null : spec.getOwnerName(),
                "mediaType", spec == null ? null : spec.getMediaType(),
                "size", spec == null ? null : spec.getSize(),
                "permalink", status == null ? null : status.getPermalink(),
                "thumbnails", status == null ? null : status.getThumbnails(),
                "version", attachment.getMetadata() == null ? null : attachment.getMetadata().getVersion());
    }

    static <T> Map<String, Object> page(ListResult<T> result, java.util.function.Function<T, ?> mapper) {
        return map(
                "items", result.getItems().stream().map(mapper).toList(),
                "page", result.getPage(),
                "size", result.getSize(),
                "total", result.getTotal(),
                "totalPages", result.getTotalPages(),
                "hasNext", result.hasNext());
    }

    private static String name(MetadataOperator metadata) {
        return metadata == null ? null : metadata.getName();
    }

    private static boolean published(MetadataOperator metadata, String label) {
        return metadata != null
                && metadata.getLabels() != null
                && Boolean.parseBoolean(metadata.getLabels().get(label));
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
