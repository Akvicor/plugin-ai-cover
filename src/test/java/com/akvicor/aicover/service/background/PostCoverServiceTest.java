package com.akvicor.aicover.service.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PostCoverServiceTest {

    @Mock
    ReactiveExtensionClient client;

    PostCoverService service;

    @BeforeEach
    void setUp() {
        service = new PostCoverService(client);
    }

    @Test
    void shouldReplaceExistingCoverAndKeepLatestOtherFields() {
        var fresh = post("/old.png");
        fresh.getSpec().setTitle("并发保存的标题");
        fresh.getMetadata().setVersion(9L);
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(post("/old.png"))).thenReturn(Mono.just(fresh));
        when(client.update(any(Post.class)))
            .thenReturn(Mono.error(new OptimisticLockingFailureException("conflict")))
            .thenAnswer(call -> Mono.just(call.getArgument(0)));

        var result = service.write("post", attachment("image"), Mode.REPLACE, false)
            .block(Duration.ofSeconds(5));

        assertThat(result.applied()).isTrue();
        var saved = ArgumentCaptor.forClass(Post.class);
        verify(client, times(2)).update(saved.capture());
        assertThat(saved.getValue().getSpec().getCover()).isEqualTo("/generated.png");
        assertThat(saved.getValue().getSpec().getTitle()).isEqualTo("并发保存的标题");
        assertThat(saved.getValue().getMetadata().getVersion()).isEqualTo(9L);
    }

    @Test
    void shouldFillMissingCoverButKeepCoverSetDuringVersionConflict() {
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(post(null))).thenReturn(Mono.just(post("/manual.png")));
        when(client.update(any(Post.class)))
            .thenReturn(Mono.error(new OptimisticLockingFailureException("conflict")));

        var result = service.write("post", attachment("image"), Mode.FILL, false)
            .block(Duration.ofSeconds(5));

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).isEqualTo("文章已有封面，已跳过");
        verify(client).update(any(Post.class));
    }

    @Test
    void shouldSkipDeletedMissingAndUnpublishedArticles() {
        var deleting = post(null);
        deleting.getMetadata().setDeletionTimestamp(Instant.now());
        var recycled = post(null);
        recycled.getSpec().setDeleted(true);
        var unpublished = post(null);
        unpublished.getMetadata().getLabels().put(Post.PUBLISHED_LABEL, "false");
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(deleting)).thenReturn(Mono.just(recycled))
            .thenReturn(Mono.empty()).thenReturn(Mono.just(unpublished));

        for (var ignored = 0; ignored < 3; ignored++) {
            assertThat(service.write("post", attachment("image"), Mode.REPLACE, false).block())
                .isEqualTo(new PostCoverService.Result(false, "文章已删除，已跳过"));
        }
        assertThat(service.write("post", attachment("image"), Mode.FILL, true).block().message())
            .isEqualTo("文章已取消发布，已跳过");
        verify(client, never()).update(any());
    }

    @Test
    void shouldDeleteOnlyUploadedAttachmentByExactName() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post("/manual.png")));
        var latest = attachment("image");
        latest.getMetadata().setVersion(3L);
        when(client.fetch(Attachment.class, "image")).thenReturn(Mono.just(latest));
        when(client.delete(latest)).thenReturn(Mono.just(latest));

        service.discard("post", attachment("image")).block();

        verify(client).fetch(Attachment.class, "image");
        verify(client).delete(latest);
    }

    @Test
    void shouldKeepAttachmentThatIsAlreadyTheCover() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post("/generated.png")));

        service.discard("post", attachment("image")).block();

        verify(client, never()).fetch(Attachment.class, "image");
        verify(client, never()).delete(any());
    }

    @Test
    void shouldIgnoreMissingNameAndSwallowDeletionErrors() {
        service.discard("post", new Attachment()).block();
        org.mockito.Mockito.verifyNoInteractions(client);

        when(client.fetch(Post.class, "post")).thenReturn(Mono.empty())
            .thenReturn(Mono.error(new IllegalStateException("读取失败")));
        when(client.fetch(Attachment.class, "image")).thenReturn(Mono.empty());
        service.discard("post", attachment("image")).block();
        service.discard("post", attachment("image")).block();
        verify(client, times(1)).fetch(Attachment.class, "image");
        verify(client, never()).delete(any());
    }

    @Test
    void shouldLogCleanupFailureWithoutStorageResponse(CapturedOutput output) {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.empty());
        var latest = attachment("image");
        when(client.fetch(Attachment.class, "image")).thenReturn(Mono.just(latest));
        when(client.delete(latest)).thenReturn(Mono.error(
            new IllegalStateException("存储响应包含私密请求内容")));

        service.discard("post", latest).block();

        assertThat(output.getAll()).contains("attachment=image", "errorType=IllegalStateException")
            .doesNotContain("存储响应包含私密请求内容", "at com.akvicor");
    }

    private Post post(String cover) {
        var post = new Post();
        post.setMetadata(new Metadata());
        post.getMetadata().setName("post");
        post.getMetadata().setLabels(new HashMap<>());
        post.getMetadata().getLabels().put(Post.PUBLISHED_LABEL, "true");
        post.setSpec(new Post.PostSpec());
        post.getSpec().setCover(cover);
        post.getSpec().setTitle("原有标题");
        return post;
    }

    private Attachment attachment(String name) {
        var attachment = new Attachment();
        attachment.setMetadata(new Metadata());
        attachment.getMetadata().setName(name);
        attachment.setStatus(new Attachment.AttachmentStatus());
        attachment.getStatus().setPermalink("/generated.png");
        return attachment;
    }
}
