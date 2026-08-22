package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class SinglePageToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    @Test
    void retriesPageStateChangeAfterReconcilerVersionConflict() {
        var stalePage = page(3L);
        var latestPage = page(4L);
        when(client.fetch(SinglePage.class, "about"))
                .thenReturn(Mono.just(stalePage), Mono.just(latestPage));
        when(client.update(stalePage))
                .thenReturn(Mono.error(new OptimisticLockingFailureException("version changed")));
        when(client.update(latestPage)).thenReturn(Mono.just(latestPage));
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);

        StepVerifier.create(tools.publish(Map.of("name", "about")))
                .assertNext(payload -> assertThat(payload.summary()).isEqualTo("Published single page about"))
                .verifyComplete();

        verify(client, times(2)).fetch(SinglePage.class, "about");
        verify(client, times(2)).update(any(SinglePage.class));
    }

    @Test
    void doesNotAdvertiseReconcilerManagedVersionAsPagePrecondition() {
        var tools = new SinglePageTools(client, new ContentSnapshots(client), authorization);
        var publishTool = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(SinglePageTools.PUBLISH_PAGE))
                .findFirst()
                .orElseThrow();
        var properties = (Map<?, ?>) publishTool.specification().tool().inputSchema().get("properties");

        assertThat(properties.containsKey("expectedVersion")).isFalse();
    }

    private static SinglePage page(long version) {
        var page = new SinglePage();
        page.setMetadata(ToolSupport.metadata("about"));
        page.getMetadata().setVersion(version);
        page.setSpec(new SinglePage.SinglePageSpec());
        return page;
    }
}
