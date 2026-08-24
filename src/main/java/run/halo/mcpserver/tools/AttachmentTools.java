package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@Component
class AttachmentTools extends ToolSupport implements ToolGroup {

    static final String LIST = "halo_list_attachments";
    static final String GET = "halo_get_attachment";
    static final String UPLOAD = "halo_upload_attachment";
    static final String DELETE = "halo_delete_attachment";

    private static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;

    private final ReactiveExtensionClient client;
    private final AttachmentService attachmentService;

    AttachmentTools(
            ReactiveExtensionClient client,
            AttachmentService attachmentService,
            McpAuthorization authorization) {
        super(authorization);
        this.client = client;
        this.attachmentService = attachmentService;
    }

    @Override
    public List<BuiltInTool> tools() {
        return List.of(listTool(), getTool(), uploadTool(), deleteTool());
    }

    Mono<ToolPayload> list(Map<String, Object> arguments) {
        var page = boundedInt(arguments, "page", 1, 1, Integer.MAX_VALUE);
        var size = boundedInt(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        var options = ListOptions.builder().fieldQuery(ExtensionUtil.notDeleting()).build();
        var pageable = PageRequestImpl.of(
                page, size, Sort.by(desc("metadata.creationTimestamp"), asc("metadata.name")));
        return client.listBy(Attachment.class, options, pageable).map(result -> payload(
                ContentPayloads.page(result, ContentPayloads::attachment),
                "Listed " + result.getItems().size() + " attachments"));
    }

    Mono<ToolPayload> get(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        return client.fetch(Attachment.class, name)
                .switchIfEmpty(notFound("Attachment", name))
                .map(attachment -> payload(ContentPayloads.attachment(attachment), "Loaded attachment " + name));
    }

    Mono<ToolPayload> upload(Map<String, Object> arguments) {
        var filename = safeFilename(requiredString(arguments, "filename"));
        var policyName = requiredString(arguments, "policyName");
        var groupName = optionalString(arguments, "groupName", null);
        var encoded = requiredString(arguments, "contentBase64");
        if (encoded.length() > 12_000_000) {
            throw new McpToolException("INVALID_ARGUMENT", "contentBase64 exceeds the 8 MiB limit");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new McpToolException("INVALID_ARGUMENT", "contentBase64 is not valid Base64", error);
        }
        if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES) {
            throw new McpToolException(
                    "INVALID_ARGUMENT", "Attachment content must be between 1 byte and 8 MiB");
        }
        var buffer = DefaultDataBufferFactory.sharedInstance.wrap(bytes);
        return attachmentService
                .upload(
                        policyName,
                        groupName,
                        filename,
                        Flux.just(buffer),
                        mediaType(arguments.get("mediaType")))
                .switchIfEmpty(Mono.error(new McpToolException(
                        "ATTACHMENT_UNAVAILABLE", "Halo did not create the attachment")))
                .map(attachment -> payload(ContentPayloads.attachment(attachment), "Uploaded attachment " + filename));
    }

    Mono<ToolPayload> delete(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = optionalLong(arguments, "expectedVersion");
        return client.fetch(Attachment.class, name)
                .switchIfEmpty(notFound("Attachment", name))
                .flatMap(attachment -> checkVersion(attachment.getMetadata().getVersion(), expectedVersion)
                        .then(client.delete(attachment)))
                .map(attachment -> payload(ContentPayloads.attachment(attachment), "Deleted attachment " + name));
    }

    private BuiltInTool listTool() {
        return tool(
                LIST,
                "List Halo attachments",
                "List attachments with deterministic pagination.",
                "查询附件",
                "分页查询附件及其存储策略、文件类型、大小和访问地址。",
                "ATTACHMENT",
                objectSchema(
                        map(
                                "page", integerSchema(1, null, 1),
                                "size", integerSchema(1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE)),
                        List.of()),
                pageOutputSchema(ContentPayloads.attachmentSchema()),
                READ_ONLY,
                this::list);
    }

    private BuiltInTool getTool() {
        return tool(
                GET,
                "Get Halo attachment",
                "Read one attachment and its resolved access metadata.",
                "读取附件",
                "读取指定附件的文件信息、存储策略和访问地址。",
                "ATTACHMENT",
                objectSchema(Map.of("name", stringSchema()), List.of("name")),
                ContentPayloads.attachmentSchema(),
                READ_ONLY,
                this::get);
    }

    private BuiltInTool uploadTool() {
        return tool(
                UPLOAD,
                "Upload Halo attachment",
                "Upload an attachment from Base64 because MCP tool arguments are JSON (maximum 8 MiB).",
                "上传附件",
                "MCP tool 参数是 JSON，因此通过 Base64 内容上传附件，最大支持 8 MiB。",
                "ATTACHMENT",
                objectSchema(
                        map(
                                "filename", stringSchema(),
                                "policyName", stringSchema(),
                                "groupName", stringSchema(),
                                "mediaType", stringSchema(),
                                "contentBase64",
                                        stringSchema(
                                                "Base64-encoded file bytes; do not include a data URL prefix.")),
                        List.of("filename", "policyName", "contentBase64")),
                ContentPayloads.attachmentSchema(),
                CREATE,
                this::upload);
    }

    private BuiltInTool deleteTool() {
        return tool(
                DELETE,
                "Delete Halo attachment",
                "Delete an attachment through Halo's finalizer-based storage cleanup flow.",
                "删除附件",
                "删除附件，并由 Halo 的终结器流程清理底层存储文件。",
                "ATTACHMENT",
                objectSchema(
                        map("name", stringSchema(), "expectedVersion", integerSchema()),
                        List.of("name")),
                ContentPayloads.attachmentSchema(),
                DESTRUCTIVE,
                this::delete);
    }

    private static MediaType mediaType(Object value) {
        if (value == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        if (!(value instanceof String text)) {
            throw new McpToolException("INVALID_ARGUMENT", "mediaType must be a string");
        }
        try {
            return MediaType.parseMediaType(text);
        } catch (IllegalArgumentException error) {
            throw new McpToolException("INVALID_ARGUMENT", "mediaType is invalid", error);
        }
    }

    private static String safeFilename(String filename) {
        if (filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new McpToolException(
                    "INVALID_ARGUMENT", "filename contains an unsafe path or is too long");
        }
        return filename;
    }
}
