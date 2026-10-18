package run.halo.aicover.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * 封面图生成任务管理。
 *
 * <p>图片生成通常需要数十秒甚至更久，同步等待容易被反向代理或 CDN 以超时中断，
 * 因此这里先把任务登记到内存并立即返回任务 ID，生成在后台完成后由前端轮询结果。
 *
 * <p>任务仅保存在当前实例内存中，并设置了存活时间；重启或超时后需要重新生成。
 */
@Slf4j
@Component
public class CoverGenerationTaskService {

    /** 已完成任务在内存中的保留时间。 */
    static final Duration TASK_TTL = Duration.ofMinutes(15);

    private final CoverImageService coverImageService;

    private final Map<String, CoverGenerationTask> tasks = new ConcurrentHashMap<>();

    public CoverGenerationTaskService(CoverImageService coverImageService) {
        this.coverImageService = coverImageService;
    }

    /**
     * 提交一个生成任务并立即返回任务 ID。
     */
    public String submit(String title, String prompt, String content) {
        evictExpired();
        var task = new CoverGenerationTask(UUID.randomUUID().toString());
        tasks.put(task.getId(), task);
        coverImageService.generate(title, prompt, content)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(task::succeed,
                error -> {
                    log.warn("封面图生成任务失败: {}", task.getId(), error);
                    task.fail(CoverGenerationErrors.describe(error, task.getId()));
                });
        return task.getId();
    }

    /**
     * 查询任务状态，未知任务返回空。
     */
    public Optional<CoverGenerationTask> get(String taskId) {
        evictExpired();
        return Optional.ofNullable(tasks.get(taskId));
    }

    private void evictExpired() {
        var threshold = Instant.now().minus(TASK_TTL);
        tasks.values().removeIf(task -> task.getCreatedAt().isBefore(threshold));
    }

}
