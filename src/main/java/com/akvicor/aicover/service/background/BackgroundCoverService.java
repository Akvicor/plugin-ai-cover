package com.akvicor.aicover.service.background;

import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 列表与发布事件共用的后台封面流程：生成后由服务端保存附件并设置封面。
 *
 * <p>列表请求在提交前校验并直接向前端返回错误；发布事件只为开启开关、已发布且没有封面的文章补封面。
 */
@Component
@RequiredArgsConstructor
public class BackgroundCoverService {

    private final ReactiveExtensionClient client;
    private final CoverSettingService settingService;
    private final PostContextService contextService;
    private final CoverAttachmentService attachmentService;
    private final PostCoverService postCoverService;

    /** 一次后台任务的来源、写入方式与设置快照。 */
    public record Request(String postName, Source source, Mode mode, CoverSettings settings) {

        boolean requirePublished() {
            return source == Source.PUBLISH;
        }
    }

    /** 后台流程结果；未设置封面时附件已清理并说明原因。 */
    public record Result(Attachment attachment, boolean applied, String message) {
    }

    /** 发布事件：开关开启、文章已发布且没有封面时才创建任务。 */
    public Mono<Request> preparePublished(String postName) {
        if (!StringUtils.hasText(postName)) {
            return Mono.empty();
        }
        return settingService.getSettings()
            .filter(CoverSettings::isAutoGenerateOnPublish)
            .flatMap(settings -> client.fetch(Post.class, postName)
                .filter(post -> PostCoverService.skipReason(post, Mode.FILL, true) == null)
                .map(post -> new Request(postName, Source.PUBLISH, Mode.FILL, settings)));
    }

    /** 列表：按提交时的最新文章与插件设置校验，校验失败直接返回给前端。 */
    public Mono<Request> prepareList(String postName, Mode mode) {
        if (!StringUtils.hasText(postName) || mode == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请提供文章标识和处理方式"));
        }
        return client.fetch(Post.class, postName)
            .filter(post -> !PostCoverService.DELETED.equals(
                PostCoverService.skipReason(post, Mode.REPLACE, false)))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在")))
            .flatMap(post -> {
                if (mode == Mode.FILL && StringUtils.hasText(post.getSpec().getCover())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "文章已有封面"));
                }
                return settingService.getSettings().flatMap(settings -> {
                    var problem = configurationProblem(settings, post);
                    return problem == null
                        ? Mono.just(new Request(postName, Source.LIST, mode, settings))
                        : Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, problem));
                });
            });
    }

    /** 读取正文：列表读取最新已保存正文，发布读取已发布正文。 */
    public Mono<PostContextService.PostContext> loadContext(Request request) {
        return client.fetch(Post.class, request.postName())
            .switchIfEmpty(Mono.error(new CoverSkippedException(PostCoverService.DELETED)))
            .flatMap(post -> {
                var reason = PostCoverService.skipReason(post, request.mode(),
                    request.requirePublished());
                if (reason != null) {
                    return Mono.error(new CoverSkippedException(reason));
                }
                var problem = configurationProblem(request.settings(), post);
                if (problem != null) {
                    return Mono.error(new IllegalStateException(problem));
                }
                return request.source() == Source.PUBLISH
                    ? contextService.loadForPublishedGeneration(request.postName())
                    : contextService.loadForGeneration(request.postName());
            });
    }

    /** 上传前与写入前分别检查文章；附件上传后未设置为封面时删除该附件。 */
    public Mono<Result> apply(Request request, String title, GeneratedImage image,
        Consumer<Stage> progress) {
        var postName = request.postName();
        return client.fetch(Post.class, postName)
            .flatMap(post -> {
                var reason = PostCoverService.skipReason(post, request.mode(),
                    request.requirePublished());
                if (reason != null) {
                    return Mono.just(new Result(null, false, reason));
                }
                var problem = configurationProblem(request.settings(), post);
                if (problem != null) {
                    return Mono.error(new IllegalStateException(problem));
                }
                progress.accept(Stage.UPLOADING);
                var settings = request.settings();
                return attachmentService.uploadGenerated(post.getSpec().getOwner(),
                        settings.getPolicyName(), settings.getGroupName(), title, image)
                    .switchIfEmpty(Mono.error(new IllegalStateException("附件上传未返回结果")))
                    .flatMap(attachment -> save(request, attachment, progress));
            })
            .defaultIfEmpty(new Result(null, false, PostCoverService.DELETED));
    }

    private Mono<Result> save(Request request, Attachment attachment, Consumer<Stage> progress) {
        var postName = request.postName();
        if (attachment.getStatus() == null
            || !StringUtils.hasText(attachment.getStatus().getPermalink())) {
            return postCoverService.discard(postName, attachment)
                .then(Mono.error(new IllegalStateException("附件没有访问地址，无法设置封面")));
        }
        progress.accept(Stage.APPLYING);
        return postCoverService.write(postName, attachment, request.mode(), request.requirePublished())
            .onErrorResume(error -> postCoverService.discard(postName, attachment)
                .then(Mono.error(error)))
            .flatMap(result -> result.applied()
                ? Mono.just(new Result(attachment, true, null))
                : postCoverService.discard(postName, attachment)
                    .thenReturn(new Result(null, false, result.message())));
    }

    private static String configurationProblem(CoverSettings settings, Post post) {
        if (!StringUtils.hasText(settings.getDescriptionModelName())
            || !StringUtils.hasText(settings.getImageModelName())
            || !StringUtils.hasText(settings.getPrompt())
            || !StringUtils.hasText(settings.getPolicyName())) {
            return "请先在插件设置中配置模型、默认提示词和存储策略";
        }
        if (!StringUtils.hasText(post.getSpec().getOwner())) {
            return "文章缺少作者，无法保存附件";
        }
        return null;
    }
}
