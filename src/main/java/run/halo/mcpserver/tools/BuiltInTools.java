package run.halo.mcpserver.tools;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Ordered catalog of Halo's built-in MCP tools. */
@Component
public class BuiltInTools {

    private final List<ToolGroup> groups;

    BuiltInTools(
            ContentSearchTools contentSearchTools,
            PostTools postTools,
            SinglePageTools singlePageTools,
            CategoryTools categoryTools,
            TagTools tagTools,
            CommentTools commentTools,
            AttachmentTools attachmentTools,
            ThemeSettingTools themeSettingTools) {
        this.groups = List.of(
                contentSearchTools,
                postTools,
                singlePageTools,
                categoryTools,
                tagTools,
                commentTools,
                attachmentTools,
                themeSettingTools);
    }

    public List<BuiltInTool> tools() {
        return groups.stream().flatMap(group -> group.tools().stream()).toList();
    }

    public List<McpStatelessServerFeatures.AsyncToolSpecification> specifications() {
        return tools().stream().map(BuiltInTool::specification).toList();
    }

    public Stream<String> names() {
        return tools().stream().map(tool -> tool.protocolTool().name());
    }
}
