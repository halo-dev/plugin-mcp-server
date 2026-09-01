package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class CategoryToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    @Test
    void createsChildCategoryWithDisplayMetadata() {
        var parent = category("parent", null, 1L);
        when(client.fetch(Category.class, "parent")).thenReturn(Mono.just(parent));
        when(client.create(any(Category.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new CategoryTools(client, authorization);

        StepVerifier.create(tools.create(ToolSupport.map(
                        "name", "child",
                        "displayName", "Child",
                        "parent", "parent",
                        "cover", "/upload/category.jpg",
                        "hideFromList", true)))
                .assertNext(payload -> assertThat(payload.data().toString())
                        .contains("name=child", "parent=parent", "cover=/upload/category.jpg", "hideFromList=true"))
                .verifyComplete();
    }

    @Test
    void rejectsCategoryParentCycle() {
        var child = category("child", "current", 1L);
        when(client.fetch(Category.class, "child")).thenReturn(Mono.just(child));
        var tools = new CategoryTools(client, authorization);

        StepVerifier.create(tools.update(Map.of("name", "current", "parent", "child")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .hasMessageContaining("category cycle"))
                .verify();
    }

    @Test
    void deletesCategoryOnlyAtTheExpectedVersion() {
        var category = category("news", null, 3L);
        when(client.fetch(Category.class, "news")).thenReturn(Mono.just(category));
        when(client.delete(category)).thenReturn(Mono.just(category));
        var tools = new CategoryTools(client, authorization);

        StepVerifier.create(tools.delete(Map.of("name", "news", "expectedVersion", 3)))
                .assertNext(payload -> assertThat(payload.summary()).isEqualTo("Deleted category news"))
                .verifyComplete();
    }

    private static Category category(String name, String parent, long version) {
        var category = new Category();
        category.setMetadata(ToolSupport.metadata(name));
        category.getMetadata().setVersion(version);
        category.setSpec(new Category.CategorySpec());
        category.getSpec().setDisplayName(name);
        category.getSpec().setSlug(name);
        category.getSpec().setParent(parent);
        category.getSpec().setPriority(0);
        return category;
    }
}
