package com.akvicor.aicover.service.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.service.CoverAttachmentService;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import com.akvicor.aicover.service.CoverGenerationTask.Source;
import com.akvicor.aicover.service.CoverGenerationTask.Stage;
import com.akvicor.aicover.service.GeneratedImage;
import com.akvicor.aicover.service.PostContextService;
import com.akvicor.aicover.setting.CoverSettingService;
import com.akvicor.aicover.setting.CoverSettings;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class BackgroundCoverServiceTest {

    @Mock
    ReactiveExtensionClient client;
    @Mock
    CoverSettingService settingService;
    @Mock
    PostContextService contextService;
    @Mock
    CoverAttachmentService attachmentService;
    @Mock
    PostCoverService postCoverService;
    @InjectMocks
    BackgroundCoverService service;

    private final GeneratedImage image = new GeneratedImage("aW1n", "image/png");

    @Test
    void shouldKeepPublishSwitchDisabledByDefault() {
        when(settingService.getSettings()).thenReturn(Mono.just(new CoverSettings()));
        assertThat(service.preparePublished("post").block()).isNull();
        verifyNoInteractions(client, contextService, attachmentService);
    }

    @Test
    void shouldPreparePublishedPostOnlyWithoutCover() {
        var settings = settings();
        settings.setAutoGenerateOnPublish(true);
        when(settingService.getSettings()).thenReturn(Mono.just(settings));
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(post(null))).thenReturn(Mono.just(post("/manual.png")));

        assertThat(service.preparePublished("post").block())
            .isEqualTo(new BackgroundCoverService.Request("post", Source.PUBLISH, Mode.FILL, settings));
        assertThat(service.preparePublished("post").block()).isNull();
    }

    @Test
    void shouldPrepareListRequestForDraftsAndExistingCovers() {
        var settings = settings();
        when(settingService.getSettings()).thenReturn(Mono.just(settings));
        var draft = post(null);
        draft.getMetadata().getLabels().put(Post.PUBLISHED_LABEL, "false");
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(draft)).thenReturn(Mono.just(post("/old.png")));

        assertThat(service.prepareList("post", Mode.FILL).block().source()).isEqualTo(Source.LIST);
        assertThat(service.prepareList("post", Mode.REPLACE).block().mode()).isEqualTo(Mode.REPLACE);
    }

    @Test
    void shouldRejectInvalidListRequestsBeforeCreatingTask() {
        when(client.fetch(Post.class, "post"))
            .thenReturn(Mono.just(post("/old.png"))).thenReturn(Mono.empty())
            .thenReturn(Mono.just(post(null)));
        when(settingService.getSettings()).thenReturn(Mono.just(new CoverSettings()));

        assertStatus(service.prepareList("post", Mode.FILL), HttpStatus.CONFLICT, "文章已有封面");
        assertStatus(service.prepareList("post", Mode.REPLACE), HttpStatus.NOT_FOUND, "文章不存在");
        assertStatus(service.prepareList("post", Mode.REPLACE), HttpStatus.BAD_REQUEST, "存储策略");
        assertStatus(service.prepareList(" ", Mode.REPLACE), HttpStatus.BAD_REQUEST, "处理方式");
        assertStatus(service.prepareList("post", null), HttpStatus.BAD_REQUEST, "处理方式");
    }

    @Test
    void shouldReadLatestContentForListAndReleaseContentForPublish() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post(null)));
        var head = new PostContextService.PostContext("标题", "最新正文");
        var release = new PostContextService.PostContext("标题", "已发布正文");
        when(contextService.loadForGeneration("post")).thenReturn(Mono.just(head));
        when(contextService.loadForPublishedGeneration("post")).thenReturn(Mono.just(release));

        assertThat(service.loadContext(request(Source.LIST, Mode.REPLACE)).block()).isEqualTo(head);
        assertThat(service.loadContext(request(Source.PUBLISH, Mode.FILL)).block()).isEqualTo(release);
    }

    @Test
    void shouldSkipFillBeforeGeneratingWhenCoverAlreadyExists() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post("/manual.png")));
        assertThatThrownBy(() -> service.loadContext(request(Source.LIST, Mode.FILL)).block())
            .isInstanceOf(CoverSkippedException.class).hasMessage("文章已有封面，已跳过");
        verifyNoInteractions(contextService);
    }

    @Test
    void shouldUploadAsAuthorAndWriteWithRequestedMode() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post("/old.png")));
        var attachment = attachment();
        when(attachmentService.uploadGenerated(eq("author"), eq("policy"), eq("group"), eq("标题"),
            eq(image))).thenReturn(Mono.just(attachment));
        when(postCoverService.write("post", attachment, Mode.REPLACE, false))
            .thenReturn(Mono.just(new PostCoverService.Result(true, null)));
        var stages = new ArrayList<Stage>();

        var result = service.apply(request(Source.LIST, Mode.REPLACE), "标题", image, stages::add).block();

        assertThat(result.applied()).isTrue();
        assertThat(result.attachment()).isSameAs(attachment);
        assertThat(stages).containsExactly(Stage.UPLOADING, Stage.APPLYING);
        verify(postCoverService, never()).discard(any(), any());
    }

    @Test
    void shouldNotUploadWhenFillTargetAlreadyHasCover() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post("/manual.png")));
        var result = service.apply(request(Source.PUBLISH, Mode.FILL), "标题", image, ignored -> { })
            .block();
        assertThat(result.applied()).isFalse();
        assertThat(result.message()).isEqualTo("文章已有封面，已跳过");
        verifyNoInteractions(attachmentService, postCoverService);
    }

    @Test
    void shouldDiscardUploadedAttachmentWhenWriteIsSkippedOrFails() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post(null)));
        var attachment = attachment();
        when(attachmentService.uploadGenerated(any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(attachment));
        when(postCoverService.write("post", attachment, Mode.FILL, true))
            .thenReturn(Mono.just(new PostCoverService.Result(false, "文章已有封面，已跳过")))
            .thenReturn(Mono.error(new IllegalStateException("写入失败")));
        when(postCoverService.discard("post", attachment)).thenReturn(Mono.empty());

        var skipped = service.apply(request(Source.PUBLISH, Mode.FILL), "标题", image, ignored -> { })
            .block();
        assertThat(skipped.applied()).isFalse();
        assertThat(skipped.attachment()).isNull();
        assertThatThrownBy(() -> service.apply(request(Source.PUBLISH, Mode.FILL), "标题", image,
            ignored -> { }).block()).hasMessageContaining("写入失败");
        verify(postCoverService, org.mockito.Mockito.times(2)).discard("post", attachment);
    }

    @Test
    void shouldDiscardAttachmentWithoutUrlAndKeepPostUntouched() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post(null)));
        var withoutUrl = new Attachment();
        when(attachmentService.uploadGenerated(any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(withoutUrl));
        when(postCoverService.discard("post", withoutUrl)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.apply(request(Source.LIST, Mode.REPLACE), "标题", image,
            ignored -> { }).block()).hasMessageContaining("没有访问地址");
        verify(postCoverService, never()).write(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void shouldSkipDeletedArticleBeforeUploading() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.empty());
        assertThat(service.apply(request(Source.LIST, Mode.REPLACE), "标题", image, ignored -> { })
            .block().message()).isEqualTo("文章已删除，已跳过");
        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldDiscardUploadedAttachmentWhenCancelledWhileApplying() {
        when(client.fetch(Post.class, "post")).thenReturn(Mono.just(post(null)));
        var attachment = attachment();
        when(attachmentService.uploadGenerated(any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(attachment));
        when(postCoverService.write("post", attachment, Mode.REPLACE, false)).thenReturn(Mono.never());
        when(postCoverService.discard("post", attachment)).thenReturn(Mono.empty());
        var subscription = service.apply(request(Source.LIST, Mode.REPLACE), "标题", image,
            ignored -> { }).subscribe();
        verify(postCoverService).write("post", attachment, Mode.REPLACE, false);
        subscription.dispose();
        verify(postCoverService).discard("post", attachment);
    }

    private void assertStatus(Mono<?> mono, HttpStatus status, String reason) {
        assertThatThrownBy(mono::block).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(status);
            assertThat(error.getReason()).contains(reason);
        });
    }

    private BackgroundCoverService.Request request(Source source, Mode mode) {
        return new BackgroundCoverService.Request("post", source, mode, settings());
    }

    private CoverSettings settings() {
        var settings = new CoverSettings();
        settings.setDescriptionModelName("language");
        settings.setImageModelName("image");
        settings.setPrompt("默认风格");
        settings.setPolicyName("policy");
        settings.setGroupName("group");
        return settings;
    }

    private Post post(String cover) {
        var post = new Post();
        post.setMetadata(new Metadata());
        post.getMetadata().setName("post");
        post.getMetadata().setLabels(new HashMap<>());
        post.getMetadata().getLabels().put(Post.PUBLISHED_LABEL, "true");
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle("标题");
        post.getSpec().setOwner("author");
        post.getSpec().setCover(cover);
        return post;
    }

    private Attachment attachment() {
        var attachment = new Attachment();
        attachment.setMetadata(new Metadata());
        attachment.getMetadata().setName("image");
        attachment.setStatus(new Attachment.AttachmentStatus());
        attachment.getStatus().setPermalink("/cover.png");
        return attachment;
    }
}
