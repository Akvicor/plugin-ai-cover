package com.akvicor.aicover.service.background;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.akvicor.aicover.service.CoverGenerationTaskService;
import run.halo.app.event.post.PostPublishedEvent;

/** 将文章发布事件交给后台任务管理，发布调用只负责投递。 */
@Component
@RequiredArgsConstructor
public class PostPublishedCoverListener {

    private final CoverGenerationTaskService taskService;

    /** 发布成功后提交后台补封面，任务错误由任务状态与日志记录。 */
    @EventListener
    public void onPublished(PostPublishedEvent event) {
        taskService.onPostPublished(event.getName());
    }
}
