package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.setting.CoverSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import tools.jackson.databind.json.JsonMapper;

/** 验证名额、FIFO、文章锁、动态设置与停用取消共同组成的调度约束。 */
class CoverGenerationConcurrencyTest {
    private final CoverImageService images = mock(CoverImageService.class);
    private final CoverGenerationTaskService service = new CoverGenerationTaskService(images,
        mock(BackgroundCoverService.class));
    private final Map<String, Sinks.One<GeneratedImage>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void prepare() {
        when(images.generate(any(), any(), any(), any(), any())).thenAnswer(call -> Mono.defer(() -> {
            String title = call.getArgument(1);
            Consumer<CoverGenerationTask.Stage> progress = call.getArgument(4);
            progress.accept(CoverGenerationTask.Stage.DESCRIBING);
            var sink = Sinks.<GeneratedImage>one();
            pending.put(title, sink);
            started.add(title);
            return sink.asMono();
        }));
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void defaultShouldRunEightAndStartQueuedTasksInOrder() {
        var tasks = new ArrayList<CoverGenerationTask>();
        for (int i = 0; i < 10; i++) {
            tasks.add(submit("post-" + i, 8));
        }
        awaitStarted(8);
        assertThat(tasks.get(8).getStage()).isEqualTo(CoverGenerationTask.Stage.QUEUED);
        assertThat(tasks.get(9).getStage()).isEqualTo(CoverGenerationTask.Stage.QUEUED);
        finish("post-0");
        awaitStarted(9);
        assertThat(started.get(8)).isEqualTo("post-8");
        assertThat(tasks.get(9).getStage()).isEqualTo(CoverGenerationTask.Stage.QUEUED);
        finish("post-1");
        awaitStarted(10);
        assertThat(started.get(9)).isEqualTo("post-9");
    }

    @Test
    void changingLimitShouldStartMoreWorkAndWaitWhenReducing() {
        submit("a", 1);
        submit("b", 1);
        submit("c", 1);
        awaitStarted(1);
        changeLimit(2);
        awaitStarted(2);
        changeLimit(1);
        finish("a");
        Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(started).hasSize(2));
        finish("b");
        awaitStarted(3);
        assertThat(started.get(2)).isEqualTo("c");
    }

    @Test
    void queuedArticleShouldRemainLockedAndFailureShouldReleaseCapacity() {
        submit("a", 1);
        var queued = submit("b", 1);
        awaitStarted(1);
        assertThatThrownBy(() -> submit("b", 1)).isInstanceOf(ResponseStatusException.class);
        assertThat(queued.isRunning()).isTrue();
        pending.get("a").tryEmitError(new IllegalStateException("model failed"));
        awaitStarted(2);
        assertThat(started.get(1)).isEqualTo("b");
    }

    @Test
    void stoppingShouldCancelQueuedAndActiveTasksWithoutStartingMore() {
        var active = submit("a", 1);
        var queued = submit("b", 1);
        awaitStarted(1);
        service.stop();
        changeLimit(8);
        assertThat(active.getStatus()).isEqualTo(CoverGenerationTask.Status.CANCELLED);
        assertThat(queued.getStatus()).isEqualTo(CoverGenerationTask.Status.CANCELLED);
        assertThat(started).containsExactly("a");
    }

    @Test
    void concurrentSubmissionsShouldNotExceedAvailableSlots() throws Exception {
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) {
                var name = "post-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    submit(name, 2);
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            awaitStarted(2);
            assertThat(service.list()).hasSize(16);
            Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(started).hasSize(2));
        }
    }

    private CoverGenerationTask submit(String name, int limit) {
        var settings = new CoverSettings();
        settings.setMaxConcurrentGenerations(limit);
        return service.submitEditor(name, name, "prompt", "content", settings);
    }

    private void changeLimit(int limit) {
        var values = JsonMapper.builder().build().createObjectNode().put("maxConcurrentGenerations", limit);
        service.onConfigUpdated(PluginConfigUpdatedEvent.builder().source(this)
            .newSettingValues(Map.of("default", values)).build());
    }

    private void finish(String name) {
        pending.get(name).tryEmitValue(new GeneratedImage("aW1n", "image/png"));
    }

    private void awaitStarted(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> started.size() == count);
    }
}
