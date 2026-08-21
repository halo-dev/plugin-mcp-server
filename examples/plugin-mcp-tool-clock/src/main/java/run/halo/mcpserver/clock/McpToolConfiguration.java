package run.halo.mcpserver.clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class McpToolConfiguration {

    @Bean
    ClockMcpToolProvider clockMcpToolProvider() {
        return new ClockMcpToolProvider();
    }
}
