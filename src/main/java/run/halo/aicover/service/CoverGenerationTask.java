package run.halo.aicover.service;

import java.time.Instant;

/**
 * 单个封面图生成任务的状态。
 *
 * <p>字段会被后台生成线程写入、由轮询线程读取，因此使用 volatile 保证可见性。
 */
public final class CoverGenerationTask {

    public enum Status {
        /** 生成中。 */
        RUNNING,
        /** 生成成功。 */
        SUCCEEDED,
        /** 生成失败。 */
        FAILED
    }

    private final String id;

    private final Instant createdAt = Instant.now();

    private volatile Status status = Status.RUNNING;

    private volatile String base64;

    private volatile String mediaType;

    private volatile String message;

    public CoverGenerationTask(String id) {
        this.id = id;
    }

    void succeed(GeneratedImage image) {
        this.base64 = image.base64();
        this.mediaType = image.mediaType();
        this.status = Status.SUCCEEDED;
    }

    void fail(String message) {
        this.message = message;
        this.status = Status.FAILED;
    }

    public String getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getBase64() {
        return base64;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getMessage() {
        return message;
    }
}
