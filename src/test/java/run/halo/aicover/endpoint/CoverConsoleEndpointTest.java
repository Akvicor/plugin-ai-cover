package run.halo.aicover.endpoint;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.aicover.service.CoverAttachmentService;
import run.halo.aicover.service.CoverGenerationTaskService;
import run.halo.aicover.service.PostContextService;
import run.halo.aicover.setting.CoverSettingService;
import run.halo.aicover.setting.CoverSettings;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 使用内存路由与模拟文章服务验证生成任务的提交边界。
 */
@ExtendWith(MockitoExtension.class)
class CoverConsoleEndpointTest {

    @Mock
    CoverGenerationTaskService taskService;

    @Mock
    CoverAttachmentService attachmentService;

    @Mock
    CoverSettingService settingService;

    @Mock
    ReactiveExtensionClient extensionClient;

    @Mock
    PostContentService contentService;

    WebTestClient http;

    @BeforeEach
    void setUp() {
        var contextService = new PostContextService(extensionClient, contentService);
        var endpoint = new CoverConsoleEndpoint(taskService, attachmentService,
            settingService, contextService);
        http = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }

    @Test
    void shouldRejectMissingPostNameWithoutSubmittingTask() {
        generate(Map.of("prompt", "风格"))
            .expectStatus().isBadRequest();

        verifyNoInteractions(extensionClient, contentService, taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void shouldRejectBlankPostNameWithoutSubmittingTask(String postName) {
        generate(Map.of("postName", postName, "prompt", "风格"))
            .expectStatus().isBadRequest();

        verifyNoInteractions(extensionClient, contentService, taskService);
    }

    @Test
    void shouldRejectUnknownArticleWithoutSubmittingTask() {
        when(extensionClient.fetch(Post.class, "post-name")).thenReturn(Mono.empty());

        generate(Map.of("postName", "post-name", "prompt", "风格"))
            .expectStatus().isNotFound();

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

        generate(Map.of("postName", "post-name", "prompt", "风格"))
            .expectStatus().is5xxServerError();

        verifyNoInteractions(taskService);
    }

    @Test
    void shouldSubmitBackendArticleWithOriginalPrompt() {
        article("后台标题", "<p>后台正文</p>");
        when(taskService.submit("后台标题", "风格", "后台正文")).thenReturn("task-id");

        generate(Map.of("postName", "post-name", "prompt", "风格"))
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.taskId").isEqualTo("task-id")
            .jsonPath("$.status").isEqualTo("RUNNING");

        verify(taskService).submit("后台标题", "风格", "后台正文");
    }

    @Test
    void shouldApplyTitleOverrideAfterBackendArticleValidation() {
        article("后台标题", "<p>后台正文</p>");
        when(taskService.submit("覆盖标题", "风格", "后台正文")).thenReturn("task-id");

        generate(Map.of("postName", "post-name", "title", "覆盖标题", "prompt", "风格"))
            .expectStatus().isAccepted();

        verify(taskService).submit("覆盖标题", "风格", "后台正文");
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

    private WebTestClient.ResponseSpec generate(Map<String, String> body) {
        return http.post().uri("/covers/generate")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
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
