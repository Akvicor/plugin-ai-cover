package run.halo.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBufferLimitException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CoverGenerationTaskServiceTest {

    @Mock
    CoverImageService coverImageService;

    @InjectMocks
    CoverGenerationTaskService taskService;

    @Test
    void shouldCompleteTaskWithGeneratedImage() {
        when(coverImageService.generate(any(), any(), any()))
            .thenReturn(Mono.just(new GeneratedImage("base64-data", "image/png")));

        var taskId = taskService.submit("标题", "提示词", "正文");

        var task = awaitTerminalState(taskId);
        assertThat(task.getStatus()).isEqualTo(CoverGenerationTask.Status.SUCCEEDED);
        assertThat(task.getBase64()).isEqualTo("base64-data");
        assertThat(task.getMediaType()).isEqualTo("image/png");
        assertThat(task.getMessage()).isNull();
    }

    @Test
    void shouldFailTaskWhenGenerationFails() {
        when(coverImageService.generate(any(), any(), any()))
            .thenReturn(Mono.error(new IllegalStateException("模型不可用")));

        var taskId = taskService.submit("标题", "提示词", "正文");

        var task = awaitTerminalState(taskId);
        assertThat(task.getStatus()).isEqualTo(CoverGenerationTask.Status.FAILED);
        assertThat(task.getMessage()).isEqualTo("模型不可用");
    }

    @Test
    void shouldReturnEmptyForUnknownTask() {
        assertThat(taskService.get("not-exist")).isEmpty();
    }

    @Test
    void shouldExplainBufferLimitErrorWrappedByRetry() {
        // 使用真实重试包装，验证任务错误能识别当前处理链路中的缓冲限制。
        var bufferLimit = new DataBufferLimitException(
            "Exceeded limit on max bytes to buffer : 1048576");
        var retriesExhausted = Exceptions.retryExhausted("Retries exhausted: 1/1", bufferLimit);
        when(coverImageService.generate(any(), any(), any()))
            .thenReturn(Mono.error(retriesExhausted));

        var taskId = taskService.submit("标题", "提示词", "正文");

        var task = awaitTerminalState(taskId);
        assertThat(task.getStatus()).isEqualTo(CoverGenerationTask.Status.FAILED);
        assertThat(task.getMessage()).contains("缓冲上限").doesNotContain("256KB");
    }

    private CoverGenerationTask awaitTerminalState(String taskId) {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            var task = taskService.get(taskId).orElseThrow();
            if (task.getStatus() != CoverGenerationTask.Status.RUNNING) {
                return task;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("任务在超时前未进入终态: " + taskId);
    }
}
