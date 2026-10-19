package com.akvicor.aicover.service;

import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 在后端读取文章的标题与正文。
 *
 * <p>只依赖文章 metadata.name，由服务端直接读取，调用方无需传入也不需要解析页面内容：
 * <ul>
 *   <li>标题：{@link ReactiveExtensionClient} 读取 Post 的 {@code spec.title}</li>
 *   <li>手动生成正文：{@link PostContentService#getHeadContent} 读取最新已保存快照</li>
 *   <li>自动生成正文：{@link PostContentService#getReleaseContent} 读取已发布快照</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PostContextService {

    private final ReactiveExtensionClient client;

    private final PostContentService postContentService;

    /**
     * 为默认配置读取文章上下文，文章标识可省略。
     *
     * @param postName 文章 metadata.name，可为空
     * @return 标题与纯文本正文；文章不存在或参数为空时返回空对象
     */
    public Mono<PostContext> loadForConfig(String postName) {
        if (!StringUtils.hasText(postName)) {
            return Mono.just(PostContext.empty());
        }
        return Mono.zip(
                client.fetch(Post.class, postName)
                    .map(PostContextService::readTitle)
                    .defaultIfEmpty(""),
                readContent(postName).onErrorReturn("")
            )
            .map(tuple -> new PostContext(tuple.getT1(), tuple.getT2()))
            .defaultIfEmpty(PostContext.empty());
    }

    /**
     * 为生成读取有效文章上下文，标识、读取结果及文章内容均在提交任务前校验。
     *
     * @param postName 当前文章的内部标识
     * @return 后台读取的标题与正文；读取失败或两者同时为空时返回错误
     */
    public Mono<PostContext> loadForGeneration(String postName) {
        return loadForGeneration(postName, this::readContent);
    }

    /** 读取已发布正文，供发布后自动封面使用，保持草稿与线上文章的边界。 */
    public Mono<PostContext> loadForPublishedGeneration(String postName) {
        return loadForGeneration(postName, name -> postContentService.getReleaseContent(name)
            .map(PostContextService::contentToText));
    }

    private Mono<PostContext> loadForGeneration(String postName,
        Function<String, Mono<String>> contentReader) {
        if (!StringUtils.hasText(postName)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "无法识别当前文章，请先保存文章并重新打开生成窗口"));
        }
        return client.fetch(Post.class, postName)
            .flatMap(post -> contentReader.apply(postName)
                .defaultIfEmpty("")
                .map(content -> new PostContext(readTitle(post), content)))
            .onErrorMap(error -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "读取当前文章失败，请稍后重试", error))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "当前文章不存在，请返回文章列表重新打开")))
            .flatMap(context -> {
                // 以后台文章内容为准，前端标题覆盖值在上下文通过校验后应用。
                if (!StringUtils.hasText(context.title()) && !StringUtils.hasText(context.content())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "文章标题和正文均为空，请先保存文章内容后再生成"));
                }
                return Mono.just(context);
            });
    }

    private Mono<String> readContent(String postName) {
        return postContentService.getHeadContent(postName)
            .map(PostContextService::contentToText);
    }

    private static String contentToText(ContentWrapper wrapper) {
        return htmlToText(StringUtils.hasText(wrapper.getContent())
            ? wrapper.getContent() : wrapper.getRaw());
    }

    private static String readTitle(Post post) {
        return post.getSpec() == null || post.getSpec().getTitle() == null
            ? "" : post.getSpec().getTitle();
    }

    /**
     * 把正文内容转换为用于提示词的纯文本。
     *
     * <p>输入通常是编辑器渲染后的 HTML，这里去掉脚本、样式与标签，还原常见实体后归一化空白。
     */
    static String htmlToText(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        var text = source
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * 文章标题与正文纯文本。
     *
     * @param title 文章标题
     * @param content 文章正文纯文本
     */
    public record PostContext(String title, String content) {

        static PostContext empty() {
            return new PostContext("", "");
        }
    }
}
