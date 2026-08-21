package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.search.SearchOption;
import run.halo.app.search.SearchResult;
import run.halo.app.search.SearchService;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class ContentSearchToolsTest {

    @Mock
    SearchService searchService;

    @Mock
    McpAuthorization authorization;

    @Test
    void searchesPostsAndPagesWithSafeDefaults() {
        var result = new SearchResult();
        result.setHits(List.of());
        result.setTotal(0L);
        result.setLimit(10);
        when(searchService.search(any())).thenReturn(Mono.just(result));
        var tools = new ContentSearchTools(searchService, authorization);

        StepVerifier.create(tools.search(Map.of("query", "Halo")))
                .assertNext(payload -> assertThat(payload.data().toString()).contains("total=0"))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(SearchOption.class);
        verify(searchService).search(captor.capture());
        assertThat(captor.getValue().getIncludeTypes())
                .containsExactly("post.content.halo.run", "singlepage.content.halo.run");
        assertThat(captor.getValue().getFilterRecycled()).isFalse();
    }
}
