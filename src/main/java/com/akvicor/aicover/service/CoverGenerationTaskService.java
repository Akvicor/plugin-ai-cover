package com.akvicor.aicover.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import com.akvicor.aicover.service.CoverGenerationTask.Source;
import com.akvicor.aicover.service.CoverGenerationTask.Status;
import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.service.background.CoverSkippedException;

/**
 * 封面图生成任务管理与文章级生图锁。
 *
 * <p>图片生成通常需要数十秒甚至更久，同步等待容易被反向代理或 CDN 以超时中断，
 * 因此任务先登记到内存并立即返回，生成在后台完成后由前端查询结果。
 *
 * <p>同一篇文章同一时间只允许一个运行中的任务，任何来源的新请求都被拒绝。任务仅保存在当前实例内存中，
 * 终态从结束时刻起保留 {@link #TASK_TTL}，在查询或登记时被动清理；重启后全部清空。
 */
@Slf4j
@Component
public class CoverGenerationTaskService {

    /** 终态从结束时刻起的保留时长；运行中的任务保留至结束。 */
    static final Duration TASK_TTL = Duration.ofMinutes(15);

    static final String LOCKED_MESSAGE = "该文章正在生成封面";

    private final CoverImageService coverImageService;

    private final BackgroundCoverService backgroundCoverService;

    private final Map<String, CoverGenerationTask> tasks = new ConcurrentHashMap<>();

    private final Map<String, CoverGenerationTask> latestByPost = new ConcurrentHashMap<>();

    private final Disposable.Composite subscriptions = Disposables.composite();

    /** 编辑器、列表和发布事件共用同一份任务登记与文章级锁。 */
    public CoverGenerationTaskService(CoverImageService coverImageService,
        BackgroundCoverService backgroundCoverService) {
        this.coverImageService = coverImageService;
        this.backgroundCoverService = backgroundCoverService;
    }

    /** 编辑器：后台只生成图片，文章锁定到图片生成结束，预览和上传由浏览器完成。 */
    public CoverGenerationTask submitEditor(String postName, String title, String prompt,
        String content) {
        var task = register(postName, Source.EDITOR, null).orElseThrow(this::locked);
        start(task, Mono.just(new PostContextService.PostContext(title, content)), prompt, null);
        return task;
    }

    /** 列表：已通过校验的请求在后台生成、保存附件并设置封面，文章锁定到流程结束。 */
    public CoverGenerationTask submitBackground(BackgroundCoverService.Request request) {
        var task = register(request.postName(), request.source(), request.mode())
            .orElseThrow(this::locked);
        startBackground(task, request);
        return task;
    }

    /** 投递发布事件；开关读取和生成在后台执行，文章已锁定时跳过。 */
    public void onPostPublished(String postName) {
        if (!StringUtils.hasText(postName) || subscriptions.isDisposed()) {
            return;
        }
        track(postName, Mono.defer(() -> backgroundCoverService.preparePublished(postName))
            .doOnNext(request -> register(postName, request.source(), request.mode())
                .ifPresent(task -> startBackground(task, request)))
            .then(), null);
    }

    /** 查询任务详情，未知或已过期的任务返回空。 */
    public synchronized Optional<CoverGenerationTask> get(String taskId) {
        evictExpired();
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 返回各文章最新的任务，供列表状态与编辑器窗口共用。 */
    public synchronized List<CoverGenerationTask> list() {
        evictExpired();
        return latestByPost.values().stream()
            .sorted(Comparator.comparing(CoverGenerationTask::getCreatedAt))
            .toList();
    }

    /** 编辑器图片上传为附件后释放内存中的图片，只保留状态信息。 */
    public synchronized void releaseImage(String taskId) {
        evictExpired();
        var task = StringUtils.hasText(taskId) ? tasks.get(taskId) : null;
        if (task != null && task.getSource() == Source.EDITOR) {
            task.releaseImage();
        }
    }

    // 判重与登记处于同一临界区，任何来源的请求都不会为同一篇文章同时启动两个任务。
    private synchronized Optional<CoverGenerationTask> register(String postName, Source source,
        Mode mode) {
        if (subscriptions.isDisposed()) {
            throw new IllegalStateException("插件已停止，无法创建封面任务");
        }
        evictExpired();
        var existing = latestByPost.get(postName);
        if (existing != null && existing.isRunning()) {
            return Optional.empty();
        }
        var task = new CoverGenerationTask(UUID.randomUUID().toString(), postName, source, mode);
        tasks.put(task.getId(), task);
        latestByPost.put(postName, task);
        return Optional.of(task);
    }

    private void startBackground(CoverGenerationTask task, BackgroundCoverService.Request request) {
        start(task, Mono.defer(() -> backgroundCoverService.loadContext(request)),
            request.settings().getPrompt(), request);
    }

    private void start(CoverGenerationTask task, Mono<PostContextService.PostContext> context,
        String prompt, BackgroundCoverService.Request request) {
        var work = context.flatMap(article -> {
                task.setTitle(article.title());
                return coverImageService.generate(article.title(), prompt, article.content(),
                        task::progress)
                    .switchIfEmpty(Mono.error(new IllegalStateException("图片模型未返回生成结果")));
            })
            .flatMap(image -> request == null
                ? finishEditor(task, image)
                : finishBackground(task, request, image))
            .then();
        track(task.getPostName(), work, task);
    }

    private Mono<Void> finishEditor(CoverGenerationTask task, GeneratedImage image) {
        task.imageReady(image);
        task.finish(Status.SUCCEEDED, null, null);
        return Mono.empty();
    }

    // 后台任务的图片只在流程内传递，保存为附件后不再保留在任务中。
    private Mono<Void> finishBackground(CoverGenerationTask task,
        BackgroundCoverService.Request request, GeneratedImage image) {
        return backgroundCoverService.apply(request, task.getTitle(), image, task::progress)
            .doOnNext(result -> task.finish(result.applied() ? Status.SUCCEEDED : Status.SKIPPED,
                result.message(), result.attachment()))
            .then();
    }

    private void track(String postName, Mono<Void> work, CoverGenerationTask task) {
        var subscription = Disposables.swap();
        if (!subscriptions.add(subscription)) {
            if (task != null) {
                task.finish(Status.CANCELLED, "插件已停止，任务已取消", null);
            }
            return;
        }
        var traceId = task == null ? UUID.randomUUID().toString() : task.getId();
        subscription.update(work.subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> {
                if (task != null && signal == SignalType.CANCEL) {
                    task.finish(Status.CANCELLED, "插件已停止，任务已取消", null);
                }
                subscriptions.remove(subscription);
            })
            .subscribe(ignored -> { }, error -> {
                if (error instanceof CoverSkippedException skipped) {
                    if (task != null) {
                        task.finish(Status.SKIPPED, skipped.getMessage(), null);
                    }
                    return;
                }
                var message = CoverGenerationErrors.describe(error, traceId);
                if (task != null) {
                    task.finish(Status.FAILED, message, null);
                }
                // 异常说明可能包含供应商回显的用户内容，日志只保留定位信息和错误类型。
                log.warn("封面后台处理失败: taskId={}, postName={}, errorType={}",
                    traceId, postName, error.getClass().getSimpleName());
            }, () -> {
                if (task != null) {
                    task.finish(Status.FAILED, "封面任务未返回结果", null);
                }
            }));
    }

    private ResponseStatusException locked() {
        return new ResponseStatusException(HttpStatus.CONFLICT, LOCKED_MESSAGE);
    }

    /** 插件上下文关闭时取消订阅，释放运行中的文章锁。 */
    @PreDestroy
    public void stop() {
        subscriptions.dispose();
        tasks.values().forEach(task -> task.finish(Status.CANCELLED, "插件已停止，任务已取消", null));
    }

    private void evictExpired() {
        var now = Instant.now();
        tasks.values().removeIf(task -> {
            if (task.isExpired(now, TASK_TTL)) {
                latestByPost.remove(task.getPostName(), task);
                return true;
            }
            return false;
        });
    }
}
