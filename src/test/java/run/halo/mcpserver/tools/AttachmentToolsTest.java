package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        var tools = new AttachmentTools(client, attachmentService, authorization);

        assertThatThrownBy(() -> tools.upload(Map.of(
                        "filename", "a.txt",
                        "policyName", "local",
                        "contentBase64", "not-base64")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void deletionUsesExtensionLifecycleSoReconcilerCleansStorage() {
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("attachment-one"));
        attachment.getMetadata().setVersion(1L);
        when(client.fetch(Attachment.class, "attachment-one")).thenReturn(Mono.just(attachment));
        when(client.delete(attachment)).thenReturn(Mono.just(attachment));
        var tools = new AttachmentTools(client, attachmentService, authorization);

        StepVerifier.create(tools.delete(Map.of("name", "attachment-one")))
                .assertNext(payload -> assertThat(payload.summary()).contains("attachment-one"))
                .verifyComplete();

        verify(client).delete(attachment);
        verify(attachmentService, never()).delete(attachment);
    }
}
