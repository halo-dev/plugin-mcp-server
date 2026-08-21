package run.halo.mcpserver.clock;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class ClockPlugin extends BasePlugin {

    public ClockPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
