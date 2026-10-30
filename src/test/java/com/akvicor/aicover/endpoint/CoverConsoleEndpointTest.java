package com.akvicor.aicover.endpoint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.service.CoverAttachmentService;
import com.akvicor.aicover.service.CoverGenerationTask;
import com.akvicor.aicover.service.CoverGenerationTask.Mode;
import com.akvicor.aicover.service.CoverGenerationTask.Source;
import com.akvicor.aicover.service.CoverGenerationTaskService;
import com.akvicor.aicover.service.PostContextService;
import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.setting.CoverSettingService;
import com.akvicor.aicover.setting.CoverSettings;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 使用内存路由与模拟服务验证生成、后台任务、状态查询和上传接口的边界。
 */
@ExtendWith(MockitoExtension.class)
class CoverConsoleEndpointTest {

    @Mock
    CoverGenerationTaskService taskService;

    @Mock
    BackgroundCoverService backgroundCoverService;

    @Mock
    CoverAttachmentService attachmentService;

    @Mock
    CoverSettingService settingService;

    @Mock
    ReactiveExtensionClient extensionClient;

    @Mock
    PostContentService contentService;

    CoverConsoleEndpoint endpoint;

    WebTestClient http;

    @BeforeEach
    void setUp() {
        var contextService = new PostContextService(extensionClient, contentService);
        endpoint = new CoverConsoleEndpoint(taskService, backgroundCoverService, attachmentService,
            settingService, contextService, extensionClient);
        http = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }

    @Test
    void shouldRejectMissingPostNameWithoutSubmittingTask() {
        generate(Map.of("prompt", "风格")).expectStatus().isBadRequest();
        verifyNoInteractions(extensionClient, contentService, taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void shouldRejectBlankPostNameWithoutSubmittingTask(String postName) {
        generate(Map.of("postName", postName, "prompt", "风格")).expectStatus().isBadRequest();
        verifyNoInteractions(extensionClient, contentService, taskService);
    }

    @Test
    void shouldRejectUnknownArticleWithoutSubmittingTask() {
        when(extensionClient.fetch(Post.class, "post-name")).thenReturn(Mono.empty());
        generate(Map.of("postName", "post-name", "prompt", "风格")).expectStatus().isNotFound();
        verifyNoInteractions(contentService, taskService);
    }

    @Test
    void shouldRejectEmptyBackendArticleEvenWhenTitleOverrideExists() {
        article(" ", "<p>&nbsp;</p>");
        generate(Map.of("postName", "post-name", "title", "前端覆盖标题", "prompt", "风格"))
            .expectStatus().isBadRequest();
        verifyNoInteractions(taskService);
    }

    @Test
    void shouldRejectContentReadFailureWithoutSubmittingTask() {
        var post = new Post();
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle("后台标题");
        when(extensionClient.fetch(Post.class, "post-name")).thenReturn(Mono.just(post));
        when(contentService.getHeadContent("post-name"))
            .thenReturn(Mono.error(new IllegalStateException("content failed")));
        generate(Map.of("postName", "post-name", "prompt", "风格")).expectStatus().is5xxServerError();
        verifyNoInteractions(taskService);
    }

    @Test
    void shouldSubmitEditorTaskWithTitleOverride() {
        article("后台标题", "<p>后台正文</p>");
        when(settingService.getSettings()).thenReturn(Mono.just(new CoverSettings()));
        when(taskService.submitEditor(eq("post-name"), eq("覆盖标题"), eq("风格"), eq("后台正文"), any()))
            .thenReturn(new CoverGenerationTask("task-id", "post-name", Source.EDITOR, null));

        generate(Map.of("postName", "post-name", "title", "覆盖标题", "prompt", "风格"))
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.taskId").isEqualTo("task-id")
            .jsonPath("$.status").isEqualTo("RUNNING")
            .jsonPath("$.source").isEqualTo("EDITOR");
    }

    @Test
    void shouldReturnConflictWhenArticleIsLocked() {
        article("后台标题", "<p>后台正文</p>");
        when(settingService.getSettings()).thenReturn(Mono.just(new CoverSettings()));
        when(taskService.submitEditor(any(), any(), any(), any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "该文章正在生成封面"));
        generate(Map.of("postName", "post-name", "prompt", "风格"))
            .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldKeepDefaultConfigAvailableWithoutArticle() {
        var settings = new CoverSettings();
        settings.setPrompt("默认风格");
        when(settingService.getSettings()).thenReturn(Mono.just(settings));

        http.get().uri("/covers/config").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.title").isEqualTo("")
            .jsonPath("$.prompt").isEqualTo("默认风格");
        verifyNoInteractions(extensionClient, contentService, taskService);
    }

    @Test
    void shouldListTaskSummariesWithoutImages() {
        when(taskService.list()).thenReturn(List.of(
            new CoverGenerationTask("list-task", "post", Source.LIST, Mode.FILL)));

        http.get().uri("/covers/tasks").exchange()
            .expectStatus().isOk().expectBody()
            .jsonPath("$.tasks[0].taskId").isEqualTo("list-task")
            .jsonPath("$.tasks[0].source").isEqualTo("LIST")
            .jsonPath("$.tasks[0].mode").isEqualTo("FILL")
            .jsonPath("$.tasks[0].stage").isEqualTo("QUEUED")
            .jsonPath("$.tasks[0].base64").isEmpty()
            .jsonPath("$.tasks[0].imageAvailable").isEqualTo(false);
        verifyNoInteractions(extensionClient, contentService);
    }

    @Test
    void shouldSubmitBackgroundTaskAfterPreparation() {
        var request = new BackgroundCoverService.Request("post", Source.LIST, Mode.REPLACE,
            new CoverSettings());
        when(backgroundCoverService.prepareList("post", Mode.REPLACE)).thenReturn(Mono.just(request));
        when(taskService.submitBackground(request))
            .thenReturn(new CoverGenerationTask("task", "post", Source.LIST, Mode.REPLACE));

        background(Map.of("postName", "post", "mode", "REPLACE"))
            .expectStatus().isAccepted().expectBody()
            .jsonPath("$.taskId").isEqualTo("task")
            .jsonPath("$.mode").isEqualTo("REPLACE");
    }

    @Test
    void shouldReturnPreparationErrorsWithoutCreatingTask() {
        when(backgroundCoverService.prepareList("post", Mode.FILL)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.CONFLICT, "文章已有封面")));

        background(Map.of("postName", "post", "mode", "FILL"))
            .expectStatus().isEqualTo(HttpStatus.CONFLICT);
        verify(taskService, never()).submitBackground(any());
    }

    @Test
    void shouldReleaseEditorImageAfterUploadSucceeds() {
        var attachment = new Attachment();
        attachment.setMetadata(new Metadata());
        attachment.getMetadata().setName("image");
        when(attachmentService.upload(eq("admin"), eq("local"), any(), eq("封面.png"), any()))
            .thenReturn(Mono.just(attachment));

        upload("task-id").expectStatus().isOk().expectBody()
            .jsonPath("$.metadata.name").isEqualTo("image");
        verify(taskService).releaseImage("task-id");
    }

    @Test
    void shouldKeepEditorImageWhenUploadFails() {
        when(attachmentService.upload(any(), any(), any(), any(), any()))
            .thenReturn(Mono.error(new IllegalStateException("存储不可用")));

        upload("task-id").expectStatus().is5xxServerError();
        verify(taskService, never()).releaseImage(any());
    }

    private WebTestClient.ResponseSpec generate(Map<String, String> body) {
        return http.post().uri("/covers/generate")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec background(Map<String, String> body) {
        return http.post().uri("/covers/background")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec upload(String taskId) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new byte[] {1, 2, 3}).filename("封面.png").contentType(MediaType.IMAGE_PNG);
        parts.part("displayName", "封面.png");
        parts.part("policyName", "local");
        parts.part("taskId", taskId);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of());
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
            .build()
            .post().uri("/covers/upload")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange();
    }

    private void article(String title, String content) {
        var post = new Post();
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle(title);
        when(extensionClient.fetch(Post.class, "post-name")).thenReturn(Mono.just(post));
        when(contentService.getHeadContent("post-name"))
            .thenReturn(Mono.just(ContentWrapper.builder().content(content).build()));
    }
}
