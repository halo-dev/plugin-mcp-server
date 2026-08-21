package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
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

        assertThat(tools.tools()).hasSize(25);
        assertThat(tools.names()).doesNotHaveDuplicates();
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
                AttachmentTools.DELETE);
        assertThat(destructive).doesNotContain(PostTools.UPDATE_POST, PostTools.PUBLISH_POST);
    }
}
