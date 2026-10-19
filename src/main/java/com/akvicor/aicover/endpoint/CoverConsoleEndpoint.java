package com.akvicor.aicover.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;
import static org.springframework.web.reactive.function.server.RequestPredicates.contentType;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.endpoint.dto.BackgroundCoverRequest;
import com.akvicor.aicover.endpoint.dto.CoverConfigResponse;
import com.akvicor.aicover.endpoint.dto.CoverTaskListResponse;
import com.akvicor.aicover.endpoint.dto.CoverTaskResponse;
import com.akvicor.aicover.endpoint.dto.GenerateCoverRequest;
import com.akvicor.aicover.endpoint.dto.UploadCoverRequest;
import com.akvicor.aicover.service.CoverAttachmentService;
import com.akvicor.aicover.service.CoverGenerationTaskService;
import com.akvicor.aicover.service.PostContextService;
import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.setting.CoverSettingService;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 供 Console 插件前端调用的自定义端点，挂在 console.api.aicover.halo.run 下。
 *
 * <p>文章上下文由后端根据 postName 读取并在生成前校验，标题可使用前端提供的覆盖值。
 */
@Component
@RequiredArgsConstructor
public class CoverConsoleEndpoint implements CustomEndpoint {

    private static final String TAG = "console.api.aicover.halo.run/v1alpha1/Cover";

    private final CoverGenerationTaskService coverGenerationTaskService;

    private final BackgroundCoverService backgroundCoverService;

    private final CoverAttachmentService coverAttachmentService;

    private final CoverSettingService coverSettingService;

    private final PostContextService postContextService;

    private final ReactiveExtensionClient client;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("covers/config", this::getConfig, builder -> builder
                .operationId("GetCoverConfig")
                .description("获取封面生成使用的默认配置，含文章标题。")
                .tag(TAG)
                .parameter(parameterBuilder().name("postName").in(ParameterIn.QUERY)
                    .required(false).implementation(String.class)
                    .description("文章 metadata.name，用于读取标题"))
                .response(responseBuilder().implementation(CoverConfigResponse.class)))
            .POST("covers/generate", this::generate, builder -> builder
                .operationId("GenerateCover")
                .description("提交封面图生成任务，立即返回任务 ID，通过任务状态接口轮询结果。")
                .tag(TAG)
                .requestBody(requestBodyBuilder().required(true)
                    .implementation(GenerateCoverRequest.class))
                .response(responseBuilder().responseCode("202")
                    .implementation(CoverTaskResponse.class)))
            .GET("covers/generate/{taskId}", this::getGenerateTask, builder -> builder
                .operationId("GetGenerateCoverTask")
                .description("查询封面图生成任务状态。")
                .tag(TAG)
                .parameter(parameterBuilder().name("taskId").in(ParameterIn.PATH)
                    .required(true).implementation(String.class))
                .response(responseBuilder().implementation(CoverTaskResponse.class)))
            .GET("covers/tasks", this::listTasks, builder -> builder
                .operationId("ListCoverTasks")
                .description("查询当前实例内各文章最新任务的摘要，不含图片数据。")
                .tag(TAG)
                .response(responseBuilder().implementation(CoverTaskListResponse.class)))
            .POST("covers/background", this::submitBackground, builder -> builder
                .operationId("SubmitBackgroundCover")
                .description("在后台生成图片、保存附件并设置文章封面；文章正在生成封面时返回 409。")
                .tag(TAG)
                .requestBody(requestBodyBuilder().required(true)
                    .implementation(BackgroundCoverRequest.class))
                .response(responseBuilder().responseCode("202")
                    .implementation(CoverTaskResponse.class)))
            .POST("covers/upload", contentType(MediaType.MULTIPART_FORM_DATA),
                this::upload, builder -> builder
                .operationId("UploadCover")
                .description("将生成的封面图上传到附件库。")
                .tag(TAG)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder()
                        .mediaType(MediaType.MULTIPART_FORM_DATA_VALUE)
                        .schema(schemaBuilder().implementation(UploadCoverRequest.class))))
                .response(responseBuilder().implementation(Attachment.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.aicover.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> getConfig(ServerRequest request) {
        var postName = request.queryParam("postName").orElse(null);
        return Mono.zip(
                coverSettingService.getSettings(),
                postContextService.loadForConfig(postName)
            )
            .map(tuple -> {
                var settings = tuple.getT1();
                var context = tuple.getT2();
                return new CoverConfigResponse(context.title(), settings.getPrompt(),
                    settings.getPolicyName(), settings.getGroupName());
            })
            .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    private Mono<ServerResponse> generate(ServerRequest request) {
        return request.bodyToMono(GenerateCoverRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请求体不能为空")))
            .flatMap(body -> resolveContext(body)
                .map(context -> {
                    var task = coverGenerationTaskService.submitEditor(body.postName(),
                        context.title(), body.prompt(), context.content());
                    return CoverTaskResponse.from(task, false);
                }))
            .flatMap(response -> ServerResponse.accepted().bodyValue(response));
    }

    /**
     * 解析本次生成使用的标题与正文。
     *
     * <p>正文始终来自后端读取的文章内容；标题优先使用请求中的覆盖值（用于附件命名与提示词），
     * 未提供时回退到文章标题。
     */
    private Mono<PostContextService.PostContext> resolveContext(GenerateCoverRequest body) {
        return postContextService.loadForGeneration(body.postName())
            .map(context -> {
                var title = StringUtils.hasText(body.title()) ? body.title() : context.title();
                return new PostContextService.PostContext(title, context.content());
            });
    }

    private Mono<ServerResponse> getGenerateTask(ServerRequest request) {
        var taskId = request.pathVariable("taskId");
        return Mono.justOrEmpty(coverGenerationTaskService.get(taskId))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "生成任务不存在或已过期")))
            .flatMap(task -> readablePost(task.getPostName())
                .thenReturn(CoverTaskResponse.from(task, true)))
            .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    private Mono<ServerResponse> listTasks(ServerRequest request) {
        var tasks = coverGenerationTaskService.list().stream()
            .map(task -> CoverTaskResponse.from(task, false))
            .toList();
        return ServerResponse.ok().bodyValue(new CoverTaskListResponse(tasks));
    }

    private Mono<ServerResponse> submitBackground(ServerRequest request) {
        return request.bodyToMono(BackgroundCoverRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请求体不能为空")))
            .flatMap(body -> backgroundCoverService.prepareList(body.postName(), body.mode()))
            .map(prepared -> CoverTaskResponse.from(
                coverGenerationTaskService.submitBackground(prepared), false))
            .flatMap(response -> ServerResponse.accepted().bodyValue(response));
    }

    private Mono<Post> readablePost(String postName) {
        if (!StringUtils.hasText(postName)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效文章标识"));
        }
        // Console 端点沿用 Halo 的角色鉴权，当前插件接口只对已有授权用户开放。
        return client.fetch(Post.class, postName)
            .filter(post -> post.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在")));
    }

    private Mono<ServerResponse> upload(ServerRequest request) {
        return request.multipartData()
            .map(UploadCoverRequest::fromParts)
            .flatMap(body -> currentUsername()
                .flatMap(username -> coverAttachmentService.upload(username, body.policyName(),
                    body.groupName(), body.displayName(), body.file()))
                // 编辑器图片已保存为附件，服务端不再保留预览数据。
                .doOnNext(ignored -> coverGenerationTaskService.releaseImage(body.taskId())))
            .flatMap(attachment -> ServerResponse.ok().bodyValue(attachment));
    }

    private Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Authentication::getName)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "需要登录后才能上传附件")));
    }
}
