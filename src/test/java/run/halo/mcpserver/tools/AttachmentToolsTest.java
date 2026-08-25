package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.McpAuthorization;
import run.halo.mcpserver.api.McpToolException;

@ExtendWith(MockitoExtension.class)
class AttachmentToolsTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    AttachmentService attachmentService;

    @Mock
    McpAuthorization authorization;

    @Test
    void validatesAttachmentBase64BeforeUpload() {
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);
        stubKeyId("key-one");

        StepVerifier.create(tools.upload(Map.<String, Object>of(
                        "filename", "a.txt",
                        "policyName", "local",
                        "contentBase64", "not-base64")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("valid Base64"))
                .verify();
        verify(attachmentService, never()).upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class));
    }

    @Test
    void rejectsUploadsWhenTheInFlightByteBudgetIsExhausted() {
        var uploadLimiter = new AttachmentUploadLimiter();
        var reservation = uploadLimiter.tryAcquire(
                "key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES);
        var tools = new AttachmentTools(client, attachmentService, uploadLimiter, authorization);
        stubKeyId("key-one");
        var arguments = Map.<String, Object>of(
                "filename", "a.txt",
                "policyName", "local",
                "contentBase64", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)));

        StepVerifier.create(tools.upload(arguments))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("concurrent attachment uploads"))
                .verify();
        verify(attachmentService, never()).upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class));

        reservation.close();
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("a.txt"));
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenReturn(Mono.just(attachment));
        StepVerifier.create(tools.upload(arguments))
                .assertNext(payload -> assertThat(payload.summary()).contains("a.txt"))
                .verifyComplete();
    }

    @Test
    void releasesTheByteBudgetWhenTheUploadIsCancelled() {
        var uploadLimiter = new AttachmentUploadLimiter();
        var tools = new AttachmentTools(client, attachmentService, uploadLimiter, authorization);
        stubKeyId("key-one");
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class))).thenReturn(Mono.never());

        StepVerifier.create(tools.upload(Map.<String, Object>of(
                        "filename", "a.txt",
                        "policyName", "local",
                        "contentBase64",
                                Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))))
                .thenCancel()
                .verify();

        assertThat(uploadLimiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
    }

    @Test
    void deletionUsesExtensionLifecycleSoReconcilerCleansStorage() {
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("attachment-one"));
        attachment.getMetadata().setVersion(1L);
        when(client.fetch(Attachment.class, "attachment-one")).thenReturn(Mono.just(attachment));
        when(client.delete(attachment)).thenReturn(Mono.just(attachment));
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);

        StepVerifier.create(tools.delete(Map.of("name", "attachment-one")))
                .assertNext(payload -> assertThat(payload.summary()).contains("attachment-one"))
                .verifyComplete();

        verify(client).delete(attachment);
        verify(attachmentService, never()).delete(attachment);
    }

    private void stubKeyId(String keyId) {
        when(authorization.withKeyId(any())).thenAnswer(invocation -> {
            java.util.function.Function<String, Mono<?>> action = invocation.getArgument(0);
            return action.apply(keyId);
        });
    }
}
