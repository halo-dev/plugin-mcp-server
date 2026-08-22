package run.halo.mcpserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerPluginTest {

    @Mock
    PluginContext context;

    @Mock
    HaloMcpServer mcpServer;

    @Mock
    SchemeManager schemeManager;

    @Mock
    McpRequestRateLimiter rateLimiter;

    @InjectMocks
    McpServerPlugin plugin;

    @Test
    void contextLoads() {
        when(mcpServer.closeGracefully()).thenReturn(Mono.empty());
        plugin.start();
        plugin.stop();
    }
}
