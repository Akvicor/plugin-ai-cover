package com.akvicor.aicover.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.akvicor.aicover.setting.CoverSettingService;
import com.akvicor.aicover.setting.CoverSettings;
import com.akvicor.aicover.service.CoverGenerationTask.Stage;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerationTimeouts;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.image.GenerateImageRequest;
import run.halo.aifoundation.image.GenerateImageResult;
import run.halo.aifoundation.image.ImageGenerationModel;
import run.halo.aifoundation.image.ImageResponseFormat;
import run.halo.aifoundation.media.GeneratedFile;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * 按「先理解、再生成」两步产出封面图。
 *
 * <ol>
 *   <li>语言模型阅读文章标题与正文，输出一段适合出图的画面描述；</li>
 *   <li>图片模型根据该画面描述与用户提示词生成封面图。</li>
 * </ol>
 *
 * <p>两步使用的模型均在插件设置中从 AI Foundation 选择。任一步失败都会直接让任务失败，
 * 不做降级，以保证出图质量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverImageService {

    private static final String DEFAULT_MEDIA_TYPE = "image/png";

    /** 单次模型调用的超时上限，避免请求无限等待。 */
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(5);

    /** 可重试的供应商调用失败次数，与 AI Foundation 控制台测试保持一致。 */
    private static final int MAX_RETRIES = 1;

    /** 第一步语言模型使用的系统提示词。 */
    private static final String DESCRIPTION_SYSTEM_PROMPT = """
        你是资深的 AI 绘画提示词工程师，擅长把技术文章转写成适合 AI 图片生成模型的画面描述。""";

    /** 第一步语言模型使用的用户提示词模板。 */
    private static final String DESCRIPTION_USER_PROMPT = """
        请阅读下面的博客文章，为它设计一段用于 AI 图片生成模型的画面描述。

        要求：
        1. 只输出画面描述本身，不要输出任何解释、前缀、标题、编号或 Markdown。
        2. 用具体的视觉语言描述画面，包括画面主体、场景、构图、色调、光线与整体风格，\
        让画面内容能体现文章主题。
        3. 描述中不得要求画面出现文字、字母、代码或水印。
        4. 使用中文，控制在 200 字以内。

        %s""";

    private final ExtensionGetter extensionGetter;

    private final CoverSettingService coverSettingService;

    private final WebClient coverImageWebClient;

    /**
     * 生成一张封面图：先用语言模型理解文章，再用图片模型出图。
     *
     * @param title 文章标题
     * @param prompt 用户在弹窗中确认的提示词，为空时使用插件设置中的默认提示词
     * @param content 文章正文纯文本
     * @param progress 真实处理阶段的观察者
     * @return 生成的图片数据（base64），模型返回 URL 时由服务端下载并转换
     */
    public Mono<GeneratedImage> generate(String title, String prompt, String content,
        Consumer<Stage> progress) {
        return coverSettingService.getSettings()
            .flatMap(settings -> {
                var effectivePrompt = StringUtils.hasText(prompt) ? prompt : settings.getPrompt();
                progress.accept(Stage.DESCRIBING);
                return describe(settings, title, content)
                    .flatMap(description -> {
                        progress.accept(Stage.GENERATING);
                        return doGenerate(settings, effectivePrompt, description);
                    });
            });
    }

    /**
     * 第一步：用语言模型阅读文章并生成画面描述。
     */
    private Mono<String> describe(CoverSettings settings, String title, String content) {
        var article = CoverPromptComposer.composeArticle(title, content);
        log.debug("正在理解文章: modelName={}", settings.getDescriptionModelName());
        return resolveLanguageModel(settings.getDescriptionModelName())
            .flatMap(model -> model.generateText(GenerateTextRequest.builder()
                .system(DESCRIPTION_SYSTEM_PROMPT)
                .prompt(DESCRIPTION_USER_PROMPT.formatted(article))
                .temperature(0.7)
                .maxOutputTokens(600)
                .maxRetries(MAX_RETRIES)
                .timeouts(GenerationTimeouts.builder()
                    .totalTimeout(GENERATION_TIMEOUT)
                    .build())
                .build()))
            .map(result -> result.getText() == null ? "" : result.getText().strip())
            .filter(StringUtils::hasText)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "语言模型没有返回画面描述，请检查所选模型是否可用")));
    }

    /**
     * 第二步：用图片模型根据画面描述与提示词生成封面图。
     */
    private Mono<GeneratedImage> doGenerate(CoverSettings settings, String prompt,
        String description) {
        var composedPrompt = CoverPromptComposer.composeImagePrompt(prompt, description);
        log.debug("正在生成封面图: modelName={}", settings.getImageModelName());
        return resolveImageModel(settings.getImageModelName())
            .flatMap(model -> model.generateImage(GenerateImageRequest.builder()
                .prompt(composedPrompt)
                .n(1)
                .aspectRatio(StringUtils.hasText(settings.getAspectRatio())
                    ? settings.getAspectRatio() : null)
                // 优先请求图片地址以减小模型响应体，图片下载由本插件管理超时和缓冲。
                // 部分模型始终返回 base64，后续转换按实际返回方式处理。
                .responseFormat(ImageResponseFormat.URL)
                .maxRetries(MAX_RETRIES)
                .timeouts(GenerationTimeouts.builder()
                    .totalTimeout(GENERATION_TIMEOUT)
                    .build())
                .build()))
            .flatMap(this::toGeneratedImage);
    }

    private Mono<LanguageModel> resolveLanguageModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return Mono.error(new IllegalStateException(
                "未配置文章理解模型，请在插件设置中选择语言模型"));
        }
        return aiModelService().flatMap(service -> service.languageModel(modelName));
    }

    private Mono<ImageGenerationModel> resolveImageModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return Mono.error(new IllegalStateException(
                "未配置图片生成模型，请在插件设置中选择图片模型"));
        }
        return aiModelService().flatMap(service -> service.imageGenerationModel(modelName));
    }

    private Mono<AiModelService> aiModelService() {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "AI Foundation 插件未启用，无法生成封面图")));
    }

    private Mono<GeneratedImage> toGeneratedImage(GenerateImageResult result) {
        var images = result.getImages();
        if (images == null || images.isEmpty()) {
            return Mono.error(new IllegalStateException("图片生成模型没有返回任何图片"));
        }
        // 供应商警告可能回显请求内容，日志只记录数量。
        if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
            log.debug("封面图生成模型返回警告: count={}", result.getWarnings().size());
        }
        var file = images.getFirst();
        if (file.isBase64()) {
            var bytes = Base64.getDecoder().decode(file.getBase64());
            return Mono.just(new GeneratedImage(file.getBase64(),
                resolveMediaType(bytes, file.getMediaType())));
        }
        var url = file.getUrl();
        if (url != null && url.regionMatches(true, 0, "data:", 0, 5)) {
            return Mono.fromCallable(() -> decodeDataUrl(url));
        }
        return download(url, file);
    }

    /** 将模型在 URL 字段返回的 Base64 图片 Data URL 解码，复用文件头识别校正媒体类型。 */
    private static GeneratedImage decodeDataUrl(String url) {
        var comma = url.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("图片 Data URL 缺少数据分隔符");
        }
        var metadata = url.substring(5, comma);
        if (!metadata.regionMatches(true, metadata.length() - 7, ";base64", 0, 7)) {
            throw new IllegalArgumentException("图片 Data URL 必须使用 Base64 编码");
        }
        final MediaType mediaType;
        final byte[] bytes;
        try {
            mediaType = MediaType.parseMediaType(metadata.substring(0, metadata.length() - 7));
            // URI 解码保留 Base64 中的加号，并支持 %2B、%2F、%3D 等转义。
            var base64 = StringUtils.uriDecode(url.substring(comma + 1), StandardCharsets.UTF_8);
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            // 解析异常可能包含原始图片数据，只向任务状态传递可操作的错误信息。
            throw new IllegalArgumentException("图片 Data URL 的媒体类型或 Base64 数据无效");
        }
        if (!"image".equalsIgnoreCase(mediaType.getType()) || !mediaType.isConcrete()) {
            throw new IllegalArgumentException("图片 Data URL 必须声明具体的 image 媒体类型");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("图片 Data URL 内容为空");
        }
        var declared = mediaType.getType() + "/" + mediaType.getSubtype();
        return new GeneratedImage(Base64.getEncoder().encodeToString(bytes),
            resolveMediaType(bytes, declared));
    }

    /**
     * 下载模型返回的图片地址。
     *
     * <p>部分供应商（例如 MiniMax）通过 URL 返回时不会给出 mediaType，因此这里按实际字节
     * 判断图片类型，避免把 JPEG 标成 PNG。
     */
    private Mono<GeneratedImage> download(String url, GeneratedFile file) {
        if (!StringUtils.hasText(url)) {
            return Mono.error(new IllegalStateException("图片生成模型返回了空的图片地址"));
        }
        return Mono.fromCallable(() -> {
                var uri = URI.create(url);
                validateRemoteUri(uri);
                return uri;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(uri -> coverImageWebClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(byte[].class))
            .map(bytes -> new GeneratedImage(
                Base64.getEncoder().encodeToString(bytes),
                resolveMediaType(bytes, file.getMediaType())));
    }

    /**
     * 解析图片的真实媒体类型。
     *
     * <p>优先使用模型声明的类型；缺失或与实际字节不符时，按文件头魔数判断，
     * 最后回退到默认类型。
     */
    private static String resolveMediaType(byte[] bytes, String declared) {
        var detected = sniffMediaType(bytes);
        if (detected != null && (declared == null || !declared.equalsIgnoreCase(detected))) {
            if (StringUtils.hasText(declared)) {
                log.debug("图片格式已按文件头校正: detected={}", detected);
            }
            return detected;
        }
        if (StringUtils.hasText(declared)) {
            return declared;
        }
        return detected != null ? detected : DEFAULT_MEDIA_TYPE;
    }

    /**
     * 按文件头魔数判断常见图片类型，无法识别时返回 null。
     */
    private static String sniffMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        return null;
    }

    /**
     * 校验模型返回的远程图片地址，限制协议并拒绝内网地址，降低 SSRF 风险。
     */
    private static void validateRemoteUri(URI uri) {
        var scheme = uri.getScheme();
        if (scheme == null
            || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("不支持的图片地址协议: " + scheme);
        }
        var host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("图片地址缺少主机名");
        }
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("不允许访问内网图片地址: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析图片地址主机: " + host, e);
        }
    }

}
