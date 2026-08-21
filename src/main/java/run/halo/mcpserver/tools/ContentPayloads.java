package run.halo.mcpserver.tools;

import static run.halo.mcpserver.tools.ToolSupport.map;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.MetadataOperator;

final class ContentPayloads {

    private ContentPayloads() {}

    static Map<String, Object> post(Post post) {
        var spec = post.getSpec();
        var status = post.getStatus();
        return map(
                "name", name(post.getMetadata()),
                "title", spec == null ? null : spec.getTitle(),
                "slug", spec == null ? null : spec.getSlug(),
                "excerpt", status == null ? null : status.getExcerpt(),
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
                "parent", spec == null ? null : spec.getParent(),
                "priority", spec == null ? null : spec.getPriority(),
                "hideFromList", spec != null && spec.isHideFromList(),
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
