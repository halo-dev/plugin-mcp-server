package run.halo.mcpserver.demo;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class DemoPlugin extends BasePlugin {

    public DemoPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
