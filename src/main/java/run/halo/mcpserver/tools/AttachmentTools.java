package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
    private static final int MAX_ENCODED_CHARACTERS = 4 * ((MAX_CONTENT_BYTES + 2) / 3);
    private static final int DECODE_BUFFER_BYTES = 64 * 1024;

    private final ReactiveExtensionClient client;
    private final AttachmentService attachmentService;
    private final AttachmentUploadLimiter uploadLimiter;

    AttachmentTools(
            ReactiveExtensionClient client,
            AttachmentService attachmentService,
            McpAuthorization authorization,
            AttachmentUploadLimiter uploadLimiter) {
        super(authorization);
        this.client = client;
        this.attachmentService = attachmentService;
        this.uploadLimiter = uploadLimiter;
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
        if (encoded.length() > MAX_ENCODED_CHARACTERS) {
            throw new McpToolException("INVALID_ARGUMENT", "contentBase64 exceeds the 8 MiB limit");
        }
        var contentBytes = decodedLength(encoded);
        if (contentBytes == 0 || contentBytes > MAX_CONTENT_BYTES) {
            throw new McpToolException(
                    "INVALID_ARGUMENT", "Attachment content must be between 1 byte and 8 MiB");
        }
        var contentType = mediaType(arguments.get("mediaType"));
        return authorization.keyId().flatMap(keyId -> Mono.using(
                () -> uploadLimiter
                        .tryAcquire(keyId, policyName, contentBytes)
                        .orElseThrow(() -> new McpToolException(
                                "RATE_LIMITED",
                                "Attachment upload capacity is exhausted; retry later")),
                ignored -> uploadReserved(
                        policyName, groupName, filename, encoded, contentType),
                AttachmentUploadLimiter.Permit::close));
    }

    private Mono<ToolPayload> uploadReserved(
            String policyName,
            String groupName,
            String filename,
            String encoded,
            MediaType contentType) {
        var content = DataBufferUtils.readInputStream(
                () -> Base64.getDecoder().wrap(new CharSequenceInputStream(encoded)),
                DefaultDataBufferFactory.sharedInstance,
                DECODE_BUFFER_BYTES);
        return attachmentService
                .upload(
                        policyName,
                        groupName,
                        filename,
                        content,
                        contentType)
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
                                        map(
                                                "type", "string",
                                                "minLength", 1,
                                                "maxLength", MAX_ENCODED_CHARACTERS,
                                                "description",
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

    private static int decodedLength(String encoded) {
        var length = encoded.length();
        var padding = 0;
        while (padding < 2
                && padding < length
                && encoded.charAt(length - padding - 1) == '=') {
            padding++;
        }
        var dataLength = length - padding;
        for (var index = 0; index < dataLength; index++) {
            if (!isBase64Character(encoded.charAt(index))) {
                throw invalidBase64();
            }
        }
        for (var index = dataLength; index < length; index++) {
            if (encoded.charAt(index) != '=') {
                throw invalidBase64();
            }
        }
        var remainder = dataLength % 4;
        if (remainder == 1
                || (padding > 0
                        && (length % 4 != 0
                                || padding != (remainder == 2 ? 2 : remainder == 3 ? 1 : 0)))) {
            throw invalidBase64();
        }
        return (dataLength / 4) * 3 + (remainder == 2 ? 1 : remainder == 3 ? 2 : 0);
    }

    private static boolean isBase64Character(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '+'
                || character == '/';
    }

    private static McpToolException invalidBase64() {
        return new McpToolException("INVALID_ARGUMENT", "contentBase64 is not valid Base64");
    }

    private static final class CharSequenceInputStream extends InputStream {

        private final CharSequence source;
        private int position;

        private CharSequenceInputStream(CharSequence source) {
            this.source = source;
        }

        @Override
        public int read() {
            return position == source.length() ? -1 : source.charAt(position++);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (position == source.length()) {
                return -1;
            }
            var count = Math.min(length, source.length() - position);
            for (var index = 0; index < count; index++) {
                bytes[offset + index] = (byte) source.charAt(position++);
            }
            return count;
        }
    }
}
