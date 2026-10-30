package com.akvicor.aicover.service;

import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import run.halo.app.core.extension.attachment.Attachment;

/**
 * 单个封面图生成任务的状态。
 *
 * <p>字段会被后台生成线程写入、由查询线程读取，因此使用 volatile 保证可见性。
 */
@Getter
public final class CoverGenerationTask {

    /** 任务终态由生成、封面写入以及插件生命周期共同决定。 */
    public enum Status {
        /** 进行中，期间文章处于锁定状态。 */
        RUNNING,
        /** 编辑器任务表示图片已生成；后台任务表示封面已设置。 */
        SUCCEEDED,
        /** 生成或写入失败。 */
        FAILED,
        /** 补封面时文章已有封面或不再符合条件。 */
        SKIPPED,
        /** 插件停止时取消任务。 */
        CANCELLED
    }

    /** 来自实际调用链的处理阶段。 */
    public enum Stage {
        /** 任务已经登记，等待后台开始处理。 */
        QUEUED,
        /** 语言模型正在理解文章。 */
        DESCRIBING,
        /** 图片模型正在生成图片。 */
        GENERATING,
        /** 正在按实际格式压缩生成的图片。 */
        COMPRESSING,
        /** 正在将图片保存到附件库。 */
        UPLOADING,
        /** 正在设置文章封面。 */
        APPLYING,
        /** 处理已经结束，具体结果由任务状态区分。 */
        COMPLETED
    }

    /** 触发任务的入口，决定图片由谁保存为附件。 */
    public enum Source {
        /** 编辑器生成窗口：后台只生成图片，由浏览器预览后上传。 */
        EDITOR,
        /** 文章列表菜单：后台生成并设置封面。 */
        LIST,
        /** 发布事件：后台为缺失封面的文章补封面。 */
        PUBLISH
    }

    /** 后台任务写入封面的方式。 */
    public enum Mode {
        /** 补封面：文章已有封面时保留并跳过。 */
        FILL,
        /** 重新生成：直接替换文章封面。 */
        REPLACE
    }

    private final String id;

    private final String postName;

    private final Source source;

    /** 编辑器任务不写入封面，该值为空。 */
    private final Mode mode;

    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.RUNNING;

    private volatile Stage stage = Stage.QUEUED;

    private volatile String title;

    private volatile Attachment attachment;

    private volatile String base64;

    private volatile String mediaType;

    private volatile String message;

    private volatile Instant finishedAt;

    /** 创建与文章关联的任务，文章级锁以运行中的任务为准。 */
    public CoverGenerationTask(String id, String postName, Source source, Mode mode) {
        this.id = id;
        this.postName = postName;
        this.source = source;
        this.mode = mode;
    }

    synchronized void imageReady(GeneratedImage image) {
        this.base64 = image.base64();
        this.mediaType = image.mediaType();
    }

    /** 图片已保存为附件或不再需要预览时释放，任务只保留状态信息。 */
    public synchronized void releaseImage() {
        this.base64 = null;
    }

    synchronized void progress(Stage stage) {
        if (status == Status.RUNNING) {
            this.stage = stage;
        }
    }

    void setTitle(String title) {
        this.title = title;
    }

    synchronized void finish(Status status, String message, Attachment attachment) {
        if (this.status != Status.RUNNING) {
            return;
        }
        this.message = message;
        this.attachment = attachment;
        this.stage = Stage.COMPLETED;
        this.status = status;
        this.finishedAt = Instant.now();
    }

    /** 运行中的任务锁定文章，结束后立即释放。 */
    public synchronized boolean isRunning() {
        return status == Status.RUNNING;
    }

    /** 终态从结束时刻开始计时，运行中的任务不会过期。 */
    synchronized boolean isExpired(Instant now, Duration ttl) {
        return finishedAt != null && finishedAt.plus(ttl).isBefore(now);
    }

    /** 一次读取一致的状态，摘要响应可以省略图片内容。 */
    public synchronized Snapshot snapshot(boolean includeImage) {
        return new Snapshot(id, postName, source, mode, title, status, stage,
            includeImage ? base64 : null, base64 != null, mediaType, message, attachment,
            createdAt, finishedAt);
    }

    /** 对外响应使用的不可变任务视图。 */
    public record Snapshot(String id, String postName, Source source, Mode mode, String title,
                           Status status, Stage stage, String base64, boolean imageAvailable,
                           String mediaType, String message, Attachment attachment,
                           Instant createdAt, Instant finishedAt) {
    }
}
