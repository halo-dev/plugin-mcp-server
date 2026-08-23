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
import run.halo.app.core.extension.content.Reply;
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

        StepVerifier.create(tools.setApproval(Map.of(
                        "name", "comment-one", "approved", true, "expectedVersion", 2)))
                .assertNext(payload -> {
                    assertThat(comment.getSpec().getApproved()).isTrue();
                    assertThat(comment.getSpec().getApprovedTime()).isNotNull();
                })
                .verifyComplete();

        StepVerifier.create(tools.setApproval(Map.of("name", "comment-one", "approved", false)))
                .assertNext(payload -> {
                    assertThat(comment.getSpec().getApproved()).isFalse();
                    assertThat(comment.getSpec().getApprovedTime()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void replyApprovalMaintainsApprovedTime() {
        var reply = new Reply();
        reply.setMetadata(ToolSupport.metadata("reply-one"));
        reply.getMetadata().setVersion(4L);
        reply.setSpec(new Reply.ReplySpec());
        reply.getSpec().setCommentName("comment-one");
        when(client.fetch(Reply.class, "reply-one")).thenReturn(Mono.just(reply));
        when(client.update(any(Reply.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var tools = new CommentTools(client, authorization);

        StepVerifier.create(tools.setReplyApproval(Map.of(
                        "name", "reply-one", "approved", true, "expectedVersion", 4)))
                .assertNext(payload -> {
                    assertThat(reply.getSpec().getApproved()).isTrue();
                    assertThat(reply.getSpec().getApprovedTime()).isNotNull();
                    assertThat(payload.data().toString()).contains("commentName=comment-one");
                })
                .verifyComplete();

        StepVerifier.create(tools.setReplyApproval(Map.of("name", "reply-one", "approved", false)))
                .assertNext(payload -> {
                    assertThat(reply.getSpec().getApproved()).isFalse();
                    assertThat(reply.getSpec().getApprovedTime()).isNull();
                })
                .verifyComplete();
    }
}
