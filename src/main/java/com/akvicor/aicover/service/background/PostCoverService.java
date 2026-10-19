package com.akvicor.aicover.service.background;

import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

/** 按写入方式更新文章封面，并清理后台流程中未设置为封面的附件。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostCoverService {

    static final String DELETED = "文章已删除，已跳过";

    static final String UNPUBLISHED = "文章已取消发布，已跳过";

    static final String HAS_COVER = "文章已有封面，已跳过";

    private final ReactiveExtensionClient client;

    /** 写入结果；未写入时说明跳过原因。 */
    public record Result(boolean applied, String message) {
    }

    /**
     * 读取最新文章后只修改封面字段，其他字段沿用最新数据。
     *
     * <p>资源版本冲突时重新读取并重新判断，补封面模式不会覆盖期间设置的封面。
     */
    public Mono<Result> write(String postName, Attachment attachment, Mode mode,
        boolean requirePublished) {
        var permalink = permalink(attachment);
        return Mono.defer(() -> client.fetch(Post.class, postName)
                .flatMap(post -> {
                    var reason = skipReason(post, mode, requirePublished);
                    if (reason != null) {
                        return Mono.just(new Result(false, reason));
                    }
                    post.getSpec().setCover(permalink);
                    return client.update(post).thenReturn(new Result(true, null));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> new Result(false, DELETED))))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance));
    }

    /**
     * 删除本次上传但未设置为封面的附件。
     *
     * <p>只按上传返回的 metadata.name 精确删除；文章当前封面仍指向该附件时保留，
     * 避免写入实际成功但响应失败时误删。删除失败只记录日志。
     */
    public Mono<Void> discard(String postName, Attachment attachment) {
        var name = attachment.getMetadata() == null ? null : attachment.getMetadata().getName();
        if (!StringUtils.hasText(name)) {
            return Mono.empty();
        }
        var permalink = permalink(attachment);
        return client.fetch(Post.class, postName)
            .map(post -> permalink != null && post.getSpec() != null
                && Objects.equals(post.getSpec().getCover(), permalink))
            .defaultIfEmpty(false)
            .filter(inUse -> !inUse)
            // Halo 删除扩展需要携带版本号，因此先读取最新的附件对象。
            .flatMap(ignored -> client.fetch(Attachment.class, name))
            .flatMap(client::delete)
            .doOnError(error -> log.warn("删除未使用的封面附件失败: attachment={}, postName={}",
                name, postName, error))
            .onErrorResume(error -> Mono.empty())
            .then();
    }

    /** 后台流程各检查点共用的文章状态判断，返回空表示可以继续。 */
    public static String skipReason(Post post, Mode mode, boolean requirePublished) {
        if (post.getMetadata().getDeletionTimestamp() != null || post.getSpec() == null
            || Boolean.TRUE.equals(post.getSpec().getDeleted())) {
            return DELETED;
        }
        if (requirePublished && !post.isPublished()) {
            return UNPUBLISHED;
        }
        if (mode == Mode.FILL && StringUtils.hasText(post.getSpec().getCover())) {
            return HAS_COVER;
        }
        return null;
    }

    private static String permalink(Attachment attachment) {
        return attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
    }
}
