package run.halo.mcpserver.tools;

import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class AttachmentTools extends ToolSupport implements ToolGroup {

    private static final Logger log = LoggerFactory.getLogger(AttachmentTools.class);

    static final String LIST = "halo_list_attachments";
    static final String GET = "halo_get_attachment";
    static final String UPLOAD = "halo_upload_attachment";
    static final String DELETE = "halo_delete_attachment";

    private static final int MAX_CONTENT_BYTES = 7 * 1024 * 1024;
    private static final int MAX_ENCODED_CHARS = (MAX_CONTENT_BYTES + 2) / 3 * 4;

    private final ReactiveExtensionClient client;
    private final AttachmentService attachmentService;
    private final AttachmentUploadLimiter uploadLimiter;

    AttachmentTools(
            ReactiveExtensionClient client,
            AttachmentService attachmentService,
            AttachmentUploadLimiter uploadLimiter,
            McpAuthorization authorization) {
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
        var encoded = requiredString(arguments, "contentBase64");
        if (encoded.length() > MAX_ENCODED_CHARS) {
            throw new McpToolException("INVALID_ARGUMENT", "contentBase64 exceeds the 7 MiB limit");
        }
        var mediaType = mediaType(arguments.get("mediaType"));
        return authorization.keyId().zipWith(attachmentConfig()).flatMap(tuple -> {
            var keyId = tuple.getT1();
            var config = tuple.getT2();
            var reservation = uploadLimiter.tryAcquire(keyId, decodedLength(encoded));
            if (reservation == null) {
                return Mono.error(new McpToolException(
                        "RATE_LIMITED", "Too many concurrent attachment uploads; please retry shortly"));
            }
            return Mono.fromCallable(() -> decode(encoded))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(bytes -> {
                        var buffer = DefaultDataBufferFactory.sharedInstance.wrap(bytes);
                        return attachmentService
                                .upload(
                                        config.policyName(),
                                        config.groupName(),
                                        filename,
                                        Flux.just(buffer),
                                        mediaType)
                                .switchIfEmpty(Mono.error(new McpToolException(
                                        "ATTACHMENT_UNAVAILABLE", "Halo did not create the attachment")))
                                .flatMap(attachment -> attachmentService
                                        .getPermalink(attachment)
                                        .doOnNext(permalink -> {
                                            if (attachment.getStatus() == null) {
                                                attachment.setStatus(new Attachment.AttachmentStatus());
                                            }
                                            attachment.getStatus().setPermalink(permalink.toString());
                                        })
                                        .onErrorResume(error -> {
                                            log.warn("Failed to resolve permalink for uploaded attachment {}",
                                                    filename, error);
                                            return Mono.empty();
                                        })
                                        .thenReturn(attachment))
                                .map(attachment -> payload(
                                        ContentPayloads.attachment(attachment),
                                        "Uploaded attachment " + filename));
                    })
                    .doFinally(ignored -> reservation.close());
        });
    }

    private static byte[] decode(String encoded) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new McpToolException("INVALID_ARGUMENT", "contentBase64 is not valid Base64", error);
        }
        if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES) {
            throw new McpToolException(
                    "INVALID_ARGUMENT", "Attachment content must be between 1 byte and 7 MiB");
        }
        return bytes;
    }

    /**
     * Returns the exact length valid Base64 decodes to. Invalid input may misestimate but never
     * below zero; the content is still validated when it is actually decoded.
     */
    private static int decodedLength(String encoded) {
        var padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        return Math.max(encoded.length() * 3 / 4 - padding, 0);
    }

    Mono<ToolPayload> delete(Map<String, Object> arguments) {
        var name = resourceName(arguments, "name");
        var expectedVersion = requiredLong(arguments, "expectedVersion");
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
                "Upload Base64 content with the Console attachment policy and group from Halo system settings (maximum 7 MiB).",
                "上传附件",
                "使用 Halo 系统设置中的 Console 附件策略和分组上传 Base64 内容，最大支持 7 MiB。",
                "ATTACHMENT",
                objectSchema(
                        map(
                                "filename", map(
                                        "type", "string",
                                        "minLength", 1,
                                        "maxLength", 255,
                                        "pattern", "^[^/\\\\\\x00-\\x1F\\x7F]+$",
                                        "description", "File name without a directory path."),
                                "mediaType", stringSchema("IANA media type; defaults to application/octet-stream."),
                                "contentBase64",
                                        map(
                                                "type", "string",
                                                "minLength", 1,
                                                "maxLength", MAX_ENCODED_CHARS,
                                                "description",
                                                        "Base64-encoded file bytes; do not include a data URL prefix.")),
                        List.of("filename", "contentBase64")),
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
                        map(
                                "name", stringSchema("Attachment metadata.name."),
                                "expectedVersion", described(
                                        integerSchema(),
                                        "Current metadata.version returned by a read or list call.")),
                        List.of("name", "expectedVersion")),
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

    private Mono<SystemSetting.Attachment.UploadOptions> attachmentConfig() {
        var defaults = client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT)
                .defaultIfEmpty(new ConfigMap());
        var overrides = client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
                .defaultIfEmpty(new ConfigMap());
        return Mono.zip(defaults, overrides)
                .map(tuple -> mergedAttachmentJson(configData(tuple.getT1()), configData(tuple.getT2())))
                .flatMap(Mono::justOrEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonMapper.shared()
                                .readValue(json, SystemSetting.Attachment.class)))
                .onErrorMap(error -> !(error instanceof McpToolException), error -> new McpToolException(
                                "ATTACHMENT_CONFIG_UNAVAILABLE",
                                "Attachment system setting is invalid",
                                error))
                .mapNotNull(SystemSetting.Attachment::console)
                .filter(config -> StringUtils.hasText(config.policyName()))
                .switchIfEmpty(Mono.error(new McpToolException(
                        "ATTACHMENT_CONFIG_UNAVAILABLE",
                        "Attachment system setting is not configured for console")));
    }

    private static Map<String, String> configData(ConfigMap configMap) {
        return configMap.getData() == null ? Map.of() : configMap.getData();
    }

    private static String mergedAttachmentJson(
            Map<String, String> defaults, Map<String, String> overrides) {
        var defaultJson = defaults.get(SystemSetting.Attachment.GROUP);
        var overrideJson = overrides.get(SystemSetting.Attachment.GROUP);
        if (defaultJson == null || overrideJson == null) {
            return overrideJson == null ? defaultJson : overrideJson;
        }
        var mapper = JsonMapper.shared();
        return mapper.writeValueAsString(deepMerge(mapper.readTree(defaultJson), mapper.readTree(overrideJson)));
    }

    private static JsonNode deepMerge(JsonNode target, JsonNode override) {
        if (!(target instanceof ObjectNode targetObject) || !(override instanceof ObjectNode overrideObject)) {
            return override;
        }
        overrideObject.properties().forEach(entry -> targetObject.set(
                entry.getKey(),
                targetObject.has(entry.getKey())
                        ? deepMerge(targetObject.get(entry.getKey()), entry.getValue())
                        : entry.getValue()));
        return targetObject;
    }
}
