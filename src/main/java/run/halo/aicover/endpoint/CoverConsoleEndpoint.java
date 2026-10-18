package run.halo.aicover.endpoint;

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
import run.halo.aicover.endpoint.dto.CoverConfigResponse;
import run.halo.aicover.endpoint.dto.CoverTaskResponse;
import run.halo.aicover.endpoint.dto.GenerateCoverRequest;
import run.halo.aicover.endpoint.dto.UploadCoverRequest;
import run.halo.aicover.service.CoverAttachmentService;
import run.halo.aicover.service.CoverGenerationTask;
import run.halo.aicover.service.CoverGenerationTaskService;
import run.halo.aicover.service.PostContextService;
import run.halo.aicover.setting.CoverSettingService;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

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

    private final CoverAttachmentService coverAttachmentService;

    private final CoverSettingService coverSettingService;

    private final PostContextService postContextService;

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
                    var taskId = coverGenerationTaskService.submit(context.title(),
                        body.prompt(), context.content());
                    return new CoverTaskResponse(taskId, CoverGenerationTask.Status.RUNNING.name(),
                        null, null, null);
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
            .map(task -> new CoverTaskResponse(task.getId(), task.getStatus().name(),
                task.getBase64(), task.getMediaType(), task.getMessage()))
            .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    private Mono<ServerResponse> upload(ServerRequest request) {
        return request.multipartData()
            .map(UploadCoverRequest::fromParts)
            .flatMap(body -> currentUsername()
                .flatMap(username -> coverAttachmentService.upload(username, body.policyName(),
                    body.groupName(), body.displayName(), body.file())))
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
