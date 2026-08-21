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
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;

@ExtendWith(MockitoExtension.class)
class CommentToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    McpAuthorization authorization;

    @Test
    void approvalMaintainsApprovedTimeLikeHaloConsole() {
        var comment = new Comment();
        comment.setMetadata(ToolSupport.metadata("comment-one"));
        comment.getMetadata().setVersion(2L);
        comment.setSpec(new Comment.CommentSpec());
        when(client.fetch(Comment.class, "comment-one")).thenReturn(Mono.just(comment));
        when(client.update(any(Comment.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new CommentTools(client, authorization);

        StepVerifier.create(tools.approve(Map.of("name", "comment-one", "expectedVersion", 2)))
                .assertNext(payload -> {
                    assertThat(comment.getSpec().getApproved()).isTrue();
                    assertThat(comment.getSpec().getApprovedTime()).isNotNull();
                })
                .verifyComplete();

        StepVerifier.create(tools.unapprove(Map.of("name", "comment-one")))
                .assertNext(payload -> {
                    assertThat(comment.getSpec().getApproved()).isFalse();
                    assertThat(comment.getSpec().getApprovedTime()).isNull();
                })
                .verifyComplete();
    }
}
