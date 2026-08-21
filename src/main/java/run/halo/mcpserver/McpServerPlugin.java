package run.halo.mcpserver;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author ryanwang
 * @since 1.0.0
 */
@Component
public class McpServerPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(McpServerPlugin.class);

    private final HaloMcpServer mcpServer;
    private final SchemeManager schemeManager;

    public McpServerPlugin(
            PluginContext pluginContext,
            HaloMcpServer mcpServer,
            SchemeManager schemeManager) {
        super(pluginContext);
        this.mcpServer = mcpServer;
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(McpAccessKey.class);
        log.info("Halo MCP server started");
    }

    @Override
    public void stop() {
        mcpServer.closeGracefully().block(Duration.ofSeconds(5));
        schemeManager.unregister(Scheme.buildFromType(McpAccessKey.class));
        log.info("Halo MCP server stopped");
    }
}
