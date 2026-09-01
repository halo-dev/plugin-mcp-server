package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ConfigMap;
import run.halo.app.infra.SystemSetting;
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
                        "contentBase64", "not-base64")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("valid Base64"))
                .verify();
        verify(attachmentService, never()).upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class));
    }

    @Test
    void rejectsContentAboveTheSevenMiBLimitBeforeUpload() {
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);

        assertThatThrownBy(() -> tools.upload(Map.of(
                        "filename", "large.bin",
                        "contentBase64", Base64.getEncoder().encodeToString(new byte[7 * 1024 * 1024 + 3]))))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("7 MiB");

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
        when(attachmentService.getPermalink(attachment))
                .thenReturn(Mono.just(URI.create("https://example.com/a.txt")));
        StepVerifier.create(tools.upload(arguments))
                .assertNext(payload -> {
                    assertThat(payload.summary()).contains("a.txt");
                    assertThat(payload.data().toString()).contains("https://example.com/a.txt");
                })
                .verifyComplete();
        verify(attachmentService).upload(
                eq("local"),
                eq("default"),
                eq("a.txt"),
                any(reactor.core.publisher.Flux.class),
                eq(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void releasesTheByteBudgetWhenTheUploadIsCancelled() throws InterruptedException {
        var uploadLimiter = new AttachmentUploadLimiter();
        var tools = new AttachmentTools(client, attachmentService, uploadLimiter, authorization);
        stubKeyId("key-one");
        var started = new CountDownLatch(1);
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenAnswer(ignored -> {
                    started.countDown();
                    return Mono.never();
                });

        var upload = tools.upload(Map.<String, Object>of(
                        "filename", "a.txt",
                        "contentBase64",
                                Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))))
                .subscribe();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        upload.dispose();

        assertThat(uploadLimiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
    }

    @Test
    void rejectsAFifthConcurrentSevenMiBUpload() throws InterruptedException {
        var uploadLimiter = new AttachmentUploadLimiter();
        var tools = new AttachmentTools(client, attachmentService, uploadLimiter, authorization);
        stubKeyId("key-one");
        var started = new CountDownLatch(4);
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenAnswer(ignored -> {
                    started.countDown();
                    return Mono.never();
                });
        var arguments = Map.<String, Object>of(
                "filename", "a.bin",
                "contentBase64", Base64.getEncoder().encodeToString(new byte[7 * 1024 * 1024]));

        var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var uploads = new java.util.ArrayList<reactor.core.Disposable>();
        for (var i = 0; i < 4; i++) {
            uploads.add(tools.upload(arguments).subscribe(payload -> {}, errors::add));
        }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // Four times 7 MiB fits the per-key budget; a fifth upload does not.
        assertThat(errors).isEmpty();
        assertThat(uploads).allSatisfy(upload -> assertThat(upload.isDisposed()).isFalse());

        StepVerifier.create(tools.upload(arguments))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(McpToolException.class)
                        .hasMessageContaining("concurrent attachment uploads"))
                .verify();

        uploads.forEach(reactor.core.Disposable::dispose);
        assertThat(uploadLimiter.tryAcquire("key-one", AttachmentUploadLimiter.PER_KEY_BUDGET_BYTES))
                .isNotNull();
    }

    @Test
    void usesTheDefaultConsoleConfigWhenNoSystemOverrideExists() {
        var defaults = new ConfigMap();
        defaults.setData(Map.of(
                SystemSetting.Attachment.GROUP,
                "{\"console\":{\"policyName\":\"default-policy\",\"groupName\":\"default-group\"}}"));
        var system = new ConfigMap();
        system.setData(Map.of());
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT))
                .thenReturn(Mono.just(defaults));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenReturn(Mono.just(system));
        when(authorization.keyId()).thenReturn(Mono.just("key-one"));
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("a.txt"));
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenReturn(Mono.just(attachment));
        when(attachmentService.getPermalink(attachment))
                .thenReturn(Mono.just(URI.create("https://example.com/a.txt")));
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);

        StepVerifier.create(tools.upload(Map.of(
                        "filename", "a.txt",
                        "contentBase64", Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)))))
                .expectNextCount(1)
                .verifyComplete();

        verify(attachmentService).upload(
                eq("default-policy"),
                eq("default-group"),
                eq("a.txt"),
                any(reactor.core.publisher.Flux.class),
                eq(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void deepMergesPartialConsoleConfigWithSystemDefaults() {
        var defaults = new ConfigMap();
        defaults.setData(Map.of(
                SystemSetting.Attachment.GROUP,
                "{\"console\":{\"policyName\":\"default-policy\",\"groupName\":\"default-group\"}}"));
        var system = new ConfigMap();
        system.setData(Map.of(
                SystemSetting.Attachment.GROUP,
                "{\"console\":{\"groupName\":\"custom-group\"}}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT))
                .thenReturn(Mono.just(defaults));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenReturn(Mono.just(system));
        when(authorization.keyId()).thenReturn(Mono.just("key-one"));
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("a.txt"));
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenReturn(Mono.just(attachment));
        when(attachmentService.getPermalink(attachment))
                .thenReturn(Mono.just(URI.create("https://example.com/a.txt")));
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);

        StepVerifier.create(tools.upload(Map.of(
                        "filename", "a.txt",
                        "contentBase64", Base64.getEncoder().encodeToString(new byte[] {1}))))
                .expectNextCount(1)
                .verifyComplete();

        verify(attachmentService).upload(
                eq("default-policy"),
                eq("custom-group"),
                eq("a.txt"),
                any(reactor.core.publisher.Flux.class),
                eq(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void keepsTheSuccessfulUploadWhenPermalinkResolutionFails() {
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);
        stubKeyId("key-one");
        var attachment = new Attachment();
        attachment.setMetadata(ToolSupport.metadata("a.txt"));
        when(attachmentService.upload(any(), any(), any(), any(reactor.core.publisher.Flux.class), any(org.springframework.http.MediaType.class)))
                .thenReturn(Mono.just(attachment));
        when(attachmentService.getPermalink(attachment))
                .thenReturn(Mono.error(new IllegalStateException("storage unavailable")));

        StepVerifier.create(tools.upload(Map.of(
                        "filename", "a.txt",
                        "contentBase64", Base64.getEncoder().encodeToString(new byte[] {1}))))
                .assertNext(payload -> assertThat(payload.summary()).contains("a.txt"))
                .verifyComplete();
    }

    @Test
    void advertisesUploadLimitsInTheInputSchema() {
        var tools = new AttachmentTools(
                client, attachmentService, new AttachmentUploadLimiter(), authorization);
        var upload = tools.tools().stream()
                .filter(tool -> tool.specification().tool().name().equals(AttachmentTools.UPLOAD))
                .findFirst()
                .orElseThrow();
        var properties = (Map<?, ?>) upload.specification().tool().inputSchema().get("properties");
        var filename = (Map<?, ?>) properties.get("filename");
        var content = (Map<?, ?>) properties.get("contentBase64");

        assertThat(filename.get("maxLength")).isEqualTo(255);
        assertThat(filename.get("pattern")).isNotNull();
        assertThat(content.get("maxLength")).isEqualTo((7 * 1024 * 1024 + 2) / 3 * 4);
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

        StepVerifier.create(tools.delete(Map.of("name", "attachment-one", "expectedVersion", 1)))
                .assertNext(payload -> assertThat(payload.summary()).contains("attachment-one"))
                .verifyComplete();

        verify(client).delete(attachment);
        verify(attachmentService, never()).delete(attachment);
    }

    private void stubKeyId(String keyId) {
        when(authorization.keyId()).thenReturn(Mono.just(keyId));
        var defaults = new ConfigMap();
        defaults.setData(Map.of());
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG_DEFAULT))
                .thenReturn(Mono.just(defaults));
        var system = new ConfigMap();
        system.setData(Map.of(
                SystemSetting.Attachment.GROUP,
                "{\"console\":{\"policyName\":\"local\",\"groupName\":\"default\"}}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(system));
    }
}
