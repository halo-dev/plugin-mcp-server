package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.search.SearchService;
import run.halo.mcpserver.McpAuthorization;

class BuiltInToolsTest {

    @Test
    void organizesToolsByHaloDomainAndUsesChineseConsoleDescriptions() {
        var client = mock(ReactiveExtensionClient.class);
        var authorization = mock(McpAuthorization.class);
        var snapshots = new ContentSnapshots(client);
        var tools = new BuiltInTools(
                new ContentSearchTools(mock(SearchService.class), authorization),
                new PostTools(client, mock(PostContentService.class), snapshots, authorization),
                new SinglePageTools(client, snapshots, authorization),
                new CategoryTools(client, authorization),
                new TagTools(client, authorization),
                new CommentTools(client, authorization),
                new AttachmentTools(client, mock(AttachmentService.class), authorization));

        var names = tools.names().toList();
        assertThat(tools.tools()).hasSize(29);
        assertThat(names)
                .doesNotHaveDuplicates()
                .contains(
                        PostTools.SET_PUBLISH_STATE,
                        SinglePageTools.SET_PUBLISH_STATE,
                        CommentTools.SET_APPROVAL,
                        CommentTools.SET_REPLY_APPROVAL)
                .doesNotContain(
                        "halo_publish_post",
                        "halo_unpublish_post",
                        "halo_publish_single_page",
                        "halo_unpublish_single_page",
                        "halo_approve_comment",
                        "halo_unapprove_comment",
                        "halo_approve_reply",
                        "halo_unapprove_reply");
        assertThat(tools.tools())
                .extracting(BuiltInTool::category)
                .contains("CONTENT_SEARCH", "POST", "PAGE", "CATEGORY", "TAG", "COMMENT", "ATTACHMENT");
        assertThat(tools.tools())
                .allSatisfy(tool -> {
                    assertThat(tool.displayTitle()).containsPattern("[\\p{IsHan}]");
                    assertThat(tool.displayDescription()).containsPattern("[\\p{IsHan}]");
                    assertThat(tool.protocolTool().description()).isNotBlank();
                });
    }

    @Test
    void marksOnlyRecycleAndDeleteToolsAsDestructive() {
        var client = mock(ReactiveExtensionClient.class);
        var authorization = mock(McpAuthorization.class);
        var snapshots = new ContentSnapshots(client);
        var tools = new BuiltInTools(
                new ContentSearchTools(mock(SearchService.class), authorization),
                new PostTools(client, mock(PostContentService.class), snapshots, authorization),
                new SinglePageTools(client, snapshots, authorization),
                new CategoryTools(client, authorization),
                new TagTools(client, authorization),
                new CommentTools(client, authorization),
                new AttachmentTools(client, mock(AttachmentService.class), authorization));

        var destructive = tools.tools().stream()
                .filter(tool -> Boolean.TRUE.equals(tool.protocolTool().annotations().destructiveHint()))
                .map(tool -> tool.protocolTool().name())
                .toList();

        assertThat(destructive).containsExactlyInAnyOrder(
                PostTools.RECYCLE_POST,
                SinglePageTools.RECYCLE_PAGE,
                CommentTools.DELETE,
                CommentTools.DELETE_REPLY,
                AttachmentTools.DELETE);
        assertThat(destructive).doesNotContain(PostTools.UPDATE_POST, PostTools.SET_PUBLISH_STATE);
    }

    @Test
    void publishesDetailedOutputObjectSchemas() {
        var client = mock(ReactiveExtensionClient.class);
        var authorization = mock(McpAuthorization.class);
        var snapshots = new ContentSnapshots(client);
        var tools = new BuiltInTools(
                new ContentSearchTools(mock(SearchService.class), authorization),
                new PostTools(client, mock(PostContentService.class), snapshots, authorization),
                new SinglePageTools(client, snapshots, authorization),
                new CategoryTools(client, authorization),
                new TagTools(client, authorization),
                new CommentTools(client, authorization),
                new AttachmentTools(client, mock(AttachmentService.class), authorization));
        var schemaValidator = new DefaultJsonSchemaValidator();

        assertThat(tools.tools()).allSatisfy(tool -> {
            var outputSchema = tool.protocolTool().outputSchema();
            var validation = schemaValidator.validateSchema(outputSchema);
            assertThat(validation.valid())
                    .as("%s output schema must be valid: %s", tool.protocolTool().name(), validation.errorMessage())
                    .isTrue();
            assertDetailedObjectSchemas(tool.protocolTool().name(), outputSchema, "$");
        });
    }

    private static void assertDetailedObjectSchemas(
            String toolName, Map<String, Object> schema, String path) {
        if ("object".equals(schema.get("type"))) {
            var properties = schema.get("properties");
            var additionalProperties = schema.get("additionalProperties");
            assertThat(properties instanceof Map<?, ?> || additionalProperties instanceof Map<?, ?>)
                    .as("%s output schema at %s must describe its object properties", toolName, path)
                    .isTrue();
            if (properties instanceof Map<?, ?> propertySchemas) {
                propertySchemas.forEach((name, propertySchema) -> {
                    if (propertySchema instanceof Map<?, ?> nestedSchema) {
                        assertThat(nestedSchema.get("description"))
                                .as("%s output property at %s must explain its meaning", toolName, path + "." + name)
                                .isInstanceOf(String.class)
                                .asString()
                                .isNotBlank();
                        assertDetailedObjectSchemas(
                                toolName, castSchema(nestedSchema), path + ".properties." + name);
                    }
                });
            }
            if (additionalProperties instanceof Map<?, ?> valueSchema) {
                assertDetailedObjectSchemas(
                        toolName, castSchema(valueSchema), path + ".additionalProperties");
            }
        }
        if ("array".equals(schema.get("type")) && schema.get("items") instanceof Map<?, ?> itemSchema) {
            assertDetailedObjectSchemas(toolName, castSchema(itemSchema), path + ".items");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSchema(Map<?, ?> schema) {
        return (Map<String, Object>) schema;
    }
}
