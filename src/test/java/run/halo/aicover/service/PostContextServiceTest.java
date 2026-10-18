package run.halo.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class PostContextServiceTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    PostContentService contentService;

    @InjectMocks
    PostContextService service;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldRejectMissingOrBlankPostNameBeforeReading(String postName) {
        assertGenerationError(postName, HttpStatus.BAD_REQUEST);
        verifyNoInteractions(client, contentService);
    }

    @Test
    void shouldRejectUnknownPostBeforeReadingContent() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.empty());

        assertGenerationError("post-name", HttpStatus.NOT_FOUND);
        verifyNoInteractions(contentService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t"})
    void shouldRejectArticleWhenTitleAndConvertedContentAreBlank(String title) {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post(title)));
        when(contentService.getHeadContent("post-name")).thenReturn(Mono.just(
            ContentWrapper.builder().content("<p>&nbsp;</p><script>hidden</script>").build()));

        assertGenerationError("post-name", HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldAllowTitleWithoutContentSnapshot() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post("文章标题")));
        when(contentService.getHeadContent("post-name")).thenReturn(Mono.empty());

        assertThat(service.loadForGeneration("post-name").block())
            .isEqualTo(new PostContextService.PostContext("文章标题", ""));
        verify(client).fetch(Post.class, "post-name");
    }

    @Test
    void shouldAllowContentWithoutTitleAndUseRawFallback() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post(null)));
        when(contentService.getHeadContent("post-name")).thenReturn(Mono.just(
            ContentWrapper.builder().content(" ").raw("<p>文章正文</p>").build()));

        assertThat(service.loadForGeneration("post-name").block())
            .isEqualTo(new PostContextService.PostContext("", "文章正文"));
    }

    @Test
    void shouldRejectEmptyArticleWithoutContentSnapshot() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post("")));
        when(contentService.getHeadContent("post-name")).thenReturn(Mono.empty());

        assertGenerationError("post-name", HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReportArticleLookupFailure() {
        when(client.fetch(Post.class, "post-name"))
            .thenReturn(Mono.error(new IllegalStateException("lookup failed")));

        assertGenerationError("post-name", HttpStatus.INTERNAL_SERVER_ERROR);
        verifyNoInteractions(contentService);
    }

    @Test
    void shouldReportContentReadFailureInsteadOfUsingEmptyContent() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post("文章标题")));
        when(contentService.getHeadContent("post-name"))
            .thenReturn(Mono.error(new IllegalStateException("content failed")));

        assertGenerationError("post-name", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldKeepOptionalArticleForConfig() {
        assertThat(service.loadForConfig(null).block())
            .isEqualTo(new PostContextService.PostContext("", ""));
        verifyNoInteractions(client, contentService);
    }

    @Test
    void shouldKeepConfigContentFailureFallback() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post("文章标题")));
        when(contentService.getHeadContent("post-name"))
            .thenReturn(Mono.error(new IllegalStateException("content failed")));

        assertThat(service.loadForConfig("post-name").block())
            .isEqualTo(new PostContextService.PostContext("文章标题", ""));
    }

    @Test
    void shouldKeepEmptySnapshotConfigResult() {
        when(client.fetch(Post.class, "post-name")).thenReturn(Mono.just(post("文章标题")));
        when(contentService.getHeadContent("post-name")).thenReturn(Mono.empty());

        assertThat(service.loadForConfig("post-name").block())
            .isEqualTo(new PostContextService.PostContext("", ""));
    }

    private void assertGenerationError(String postName, HttpStatus status) {
        assertThatThrownBy(() -> service.loadForGeneration(postName).block())
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(status);
                assertThat(error.getBody().getDetail()).isNotBlank();
            });
    }

    private static Post post(String title) {
        var post = new Post();
        post.setSpec(new Post.PostSpec());
        post.getSpec().setTitle(title);
        return post;
    }

    @Test
    void shouldStripTagsAndNormalizeWhitespace() {
        var html = "<h2 id=\"hello\"><strong>Hello</strong> Halo</h2>\n"
            + "<p>第一段</p><p>第二段</p>";

        assertThat(PostContextService.htmlToText(html)).isEqualTo("Hello Halo 第一段 第二段");
    }

    @Test
    void shouldRemoveScriptAndStyleContent() {
        var html = "<style>.a{color:red}</style><p>正文</p><script>alert(1)</script>";

        assertThat(PostContextService.htmlToText(html)).isEqualTo("正文");
    }

    @Test
    void shouldDecodeCommonEntities() {
        var html = "<p>a &amp; b &lt;c&gt; &quot;d&quot;&nbsp;e</p>";

        assertThat(PostContextService.htmlToText(html)).isEqualTo("a & b <c> \"d\" e");
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        assertThat(PostContextService.htmlToText(null)).isEmpty();
        assertThat(PostContextService.htmlToText("   ")).isEmpty();
    }
}
