package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import com.akvicor.aicover.service.CoverGenerationTask.Source;
import com.akvicor.aicover.service.CoverGenerationTask.Status;
import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.service.background.CoverSkippedException;
import com.akvicor.aicover.setting.CoverSettings;
import run.halo.app.core.extension.attachment.Attachment;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CoverGenerationTaskServiceTest {

    @Mock
    CoverImageService coverImageService;

    @Mock
    BackgroundCoverService backgroundCoverService;

    @InjectMocks
    CoverGenerationTaskService taskService;

    private final GeneratedImage image = new GeneratedImage("aW1n", "image/png");

    @AfterEach
    void tearDown() {
        taskService.stop();
    }

    @Test
    void shouldKeepEditorImageUntilUploadedAndReleaseLockWhenImageIsReady() {
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(Mono.just(image));

        var task = awaitTerminalState(submitEditor("post", "标题", "提示词", "正文"));

        assertThat(task.getStatus()).isEqualTo(Status.SUCCEEDED);
        assertThat(task.getSource()).isEqualTo(Source.EDITOR);
        assertThat(task.snapshot(true).base64()).isEqualTo("aW1n");
        assertThat(task.getFinishedAt()).isNotNull();
        taskService.releaseImage(task.getId());
        assertThat(task.snapshot(true).base64()).isNull();
        assertThat(task.snapshot(false).imageAvailable()).isFalse();
        assertThat(task.getStatus()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void shouldRejectEverySourceWhileArticleIsLockedAndAcceptAfterFailure() {
        var pending = Sinks.<GeneratedImage>one();
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(pending.asMono());
        var running = submitEditor("post", "标题", "提示词", "正文");

        assertThatThrownBy(() -> submitEditor("post", "其他", "风格", "正文"))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).isEqualTo("该文章正在生成封面");
            });
        assertThatThrownBy(() -> taskService.submitBackground(listRequest(Mode.REPLACE)))
            .isInstanceOf(ResponseStatusException.class);
        when(backgroundCoverService.preparePublished("post"))
            .thenReturn(Mono.just(request(Source.PUBLISH, Mode.FILL)));
        taskService.onPostPublished("post");
        verify(backgroundCoverService, timeout(2000)).preparePublished("post");
        verify(backgroundCoverService, never()).loadContext(any());
        assertThat(taskService.list()).containsExactly(running);

        verify(coverImageService, timeout(2000)).generate(any(), any(), any(), any(), any());
        pending.tryEmitError(new IllegalStateException("模型不可用"));
        assertThat(awaitTerminalState(running).getStatus()).isEqualTo(Status.FAILED);
        var next = submitEditor("post", "标题", "提示词", "正文");
        assertThat(next.getId()).isNotEqualTo(running.getId());
        assertThat(taskService.list()).containsExactly(next);
    }

    @Test
    void shouldKeepLockForBackgroundTaskUntilCoverIsSet() {
        var request = listRequest(Mode.REPLACE);
        when(backgroundCoverService.loadContext(request)).thenReturn(Mono.just(
            new PostContextService.PostContext("标题", "正文")));
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(Mono.just(image));
        var applied = Sinks.<BackgroundCoverService.Result>one();
        when(backgroundCoverService.apply(any(), any(), any(), any())).thenReturn(applied.asMono());

        var task = taskService.submitBackground(request);
        verify(backgroundCoverService, timeout(2000)).apply(any(), any(), any(), any());
        assertThat(task.isRunning()).isTrue();
        assertThatThrownBy(() -> submitEditor("post", "标题", "风格", "正文"))
            .isInstanceOf(ResponseStatusException.class);

        var attachment = new Attachment();
        applied.tryEmitValue(new BackgroundCoverService.Result(attachment, true, null));
        var result = awaitTerminalState(task);
        assertThat(result.getStatus()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.getAttachment()).isSameAs(attachment);
        assertThat(result.snapshot(false).imageAvailable()).isFalse();
        assertThat(result.getMode()).isEqualTo(Mode.REPLACE);
    }

    @Test
    void shouldRecordSkippedFillWithReason() {
        var request = listRequest(Mode.FILL);
        when(backgroundCoverService.loadContext(request))
            .thenReturn(Mono.error(new CoverSkippedException("文章已有封面，已跳过")));

        var task = awaitTerminalState(taskService.submitBackground(request));

        assertThat(task.getStatus()).isEqualTo(Status.SKIPPED);
        assertThat(task.getMessage()).isEqualTo("文章已有封面，已跳过");
        verifyNoInteractions(coverImageService);
    }

    @Test
    void shouldReportSkippedWriteAsSkipped() {
        var request = listRequest(Mode.FILL);
        when(backgroundCoverService.loadContext(request)).thenReturn(Mono.just(
            new PostContextService.PostContext("标题", "正文")));
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(Mono.just(image));
        when(backgroundCoverService.apply(any(), any(), any(), any())).thenReturn(Mono.just(
            new BackgroundCoverService.Result(null, false, "文章已有封面，已跳过")));

        var task = awaitTerminalState(taskService.submitBackground(request));

        assertThat(task.getStatus()).isEqualTo(Status.SKIPPED);
        assertThat(task.getAttachment()).isNull();
    }

    @Test
    void shouldShowReadableReasonsForFailures() {
        var bufferLimit = new DataBufferLimitException("Exceeded limit on max bytes to buffer : 1048576");
        when(coverImageService.generate(any(), any(), any(), any(), any()))
            .thenReturn(Mono.error(Exceptions.retryExhausted("Retries exhausted: 1/1", bufferLimit)))
            .thenReturn(Mono.empty());

        var buffer = awaitTerminalState(submitEditor("post", "标题", "提示词", "正文"));
        assertThat(buffer.getStatus()).isEqualTo(Status.FAILED);
        assertThat(buffer.getMessage()).contains("缓冲上限");

        var empty = awaitTerminalState(submitEditor("post", "标题", "提示词", "正文"));
        assertThat(empty.getMessage()).contains("未返回生成结果");
    }

    @Test
    void shouldStartPublishedTaskOnlyWhenPrepared() {
        when(backgroundCoverService.preparePublished("post")).thenReturn(Mono.empty());
        taskService.onPostPublished("post");
        verify(backgroundCoverService, timeout(2000)).preparePublished("post");
        assertThat(taskService.list()).isEmpty();

        var request = request(Source.PUBLISH, Mode.FILL);
        when(backgroundCoverService.preparePublished("other")).thenReturn(Mono.just(request));
        when(backgroundCoverService.loadContext(request))
            .thenReturn(Mono.error(new IllegalStateException("请先在插件设置中配置模型、默认提示词和存储策略")));
        org.assertj.core.api.Assertions.assertThatCode(() -> taskService.onPostPublished("other"))
            .doesNotThrowAnyException();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !taskService.list().isEmpty());
        var task = awaitTerminalState(taskService.list().getFirst());
        assertThat(task.getSource()).isEqualTo(Source.PUBLISH);
        assertThat(task.getStatus()).isEqualTo(Status.FAILED);
        assertThat(task.getMessage()).contains("存储策略");
    }

    @Test
    void shouldLogFailureWithoutProviderEchoOrStackTrace(CapturedOutput output) {
        var error = new IllegalStateException("供应商回显：未发布草稿与私密提示词");
        error.addSuppressed(new IllegalArgumentException("请求体包含保密正文"));
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(Mono.error(error));

        var task = awaitTerminalState(submitEditor("post", "标题", "提示词", "正文"));

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(output.getAll()).contains(task.getId(), "errorType=IllegalStateException"));
        assertThat(task.getStatus()).isEqualTo(Status.FAILED);
        assertThat(output.getAll()).doesNotContain("未发布草稿", "私密提示词", "保密正文",
            "Suppressed:");
    }

    @Test
    void shouldExpireTerminalStateFromFinishTimeOnlyWhenRead() throws Exception {
        var pending = Sinks.<GeneratedImage>one();
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(pending.asMono());
        var task = submitEditor("post", "标题", "提示词", "正文");
        // 运行中的任务即使创建很久也不会被清理。
        setField(task, "createdAt", Instant.now().minus(Duration.ofHours(1)));
        assertThat(taskService.list()).containsExactly(task);

        verify(coverImageService, timeout(2000)).generate(any(), any(), any(), any(), any());
        pending.tryEmitValue(image);
        awaitTerminalState(task);
        setField(task, "finishedAt", Instant.now().minus(Duration.ofMinutes(14)));
        assertThat(taskService.get(task.getId())).contains(task);

        setField(task, "finishedAt", Instant.now().minus(Duration.ofMinutes(16)));
        assertThat(taskService.get(task.getId())).isEmpty();
        assertThat(taskService.list()).isEmpty();
    }

    @Test
    void shouldCancelRunningWorkAndReleaseLockOnPluginStop() {
        var cancelled = new CountDownLatch(1);
        when(coverImageService.generate(any(), any(), any(), any(), any()))
            .thenReturn(Mono.<GeneratedImage>never().doOnCancel(cancelled::countDown));
        var task = submitEditor("post", "标题", "风格", "正文");
        verify(coverImageService, timeout(2000)).generate(any(), any(), any(), any(), any());

        taskService.stop();

        assertThat(task.getStatus()).isEqualTo(Status.CANCELLED);
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> cancelled.getCount() == 0);
        taskService.onPostPublished("post");
        verifyNoInteractions(backgroundCoverService);
    }

    @Test
    void shouldReleaseOnlyEditorImages() {
        when(coverImageService.generate(any(), any(), any(), any(), any())).thenReturn(Mono.just(image));
        var task = awaitTerminalState(submitEditor("post", "标题", "提示词", "正文"));
        taskService.releaseImage(null);
        taskService.releaseImage("unknown");
        assertThat(task.snapshot(false).imageAvailable()).isTrue();
    }

    private CoverGenerationTask submitEditor(String post, String title, String prompt, String content) {
        return taskService.submitEditor(post, title, prompt, content, new CoverSettings());
    }

    private BackgroundCoverService.Request listRequest(Mode mode) {
        return request(Source.LIST, mode);
    }

    private BackgroundCoverService.Request request(Source source, Mode mode) {
        var settings = new CoverSettings();
        settings.setPrompt("默认风格");
        return new BackgroundCoverService.Request("post", source, mode, settings);
    }

    private CoverGenerationTask awaitTerminalState(CoverGenerationTask task) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !task.isRunning());
        return task;
    }

    private static void setField(CoverGenerationTask task, String name, Instant value)
        throws ReflectiveOperationException {
        Field field = CoverGenerationTask.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(task, value);
    }
}
