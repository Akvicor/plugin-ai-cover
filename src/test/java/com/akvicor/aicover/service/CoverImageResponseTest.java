package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import com.akvicor.aicover.image.png.PngCompressor;
import com.akvicor.aicover.setting.PngCompressionMode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.setting.CoverSettings;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.image.GenerateImageRequest;
import run.halo.aifoundation.image.GenerateImageResult;
import run.halo.aifoundation.image.ImageGenerationModel;
import run.halo.aifoundation.media.GeneratedFile;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/** 通过生成入口验证模型图片返回格式，以及内嵌图片与远程下载的分流。 */
@ExtendWith(MockitoExtension.class)
class CoverImageResponseTest {

    @Mock
    ExtensionGetter extensionGetter;

    @Mock
    AiModelService modelService;

    @Mock
    LanguageModel languageModel;

    @Mock
    ImageGenerationModel imageModel;

    @Mock
    ExchangeFunction exchangeFunction;

    CoverImageService service;

    CoverSettings settings;

    @BeforeEach
    void setUp() {
        service = new CoverImageService(extensionGetter,
            WebClient.builder().exchangeFunction(exchangeFunction).build(), new PngCompressor());
        settings = new CoverSettings();
        settings.setDescriptionModelName("language");
        settings.setImageModelName("image");
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.languageModel("language")).thenReturn(Mono.just(languageModel));
        when(modelService.imageGenerationModel("image")).thenReturn(Mono.just(imageModel));
        when(languageModel.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.just(
            GenerateTextResult.builder().text("画面描述").build()));
    }

    @ParameterizedTest
    @CsvSource({
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==,image/png",
        "DATA:image/jpeg;BASE64,/9j/4AAQSkZJRgABAQ==,image/jpeg",
        "data:image/webp;base64,UklGRgAAAABXRUJQ, image/webp",
        "data:image/png;base64,/9j/4AAQSkZJRgABAQ==,image/jpeg",
        "data:image/jpeg;charset=UTF-8;base64,/9j/4AAQSkZJRgABAQ==,image/jpeg",
        "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ+/,image/jpeg",
        "data:image/jpeg;base64,%2F9j%2F4AAQSkZJRgABAQ%2B%2F,image/jpeg"
    })
    void shouldDecodeDataUrlWithoutDownloading(String prefix, String payload, String mediaType) {
        // 独立声明故意使用 PNG，验证 Data URL 自带类型和实际文件头决定最终格式。
        var image = generate(GeneratedFile.url(prefix + "," + payload, "image/png"));

        assertThat(image.mediaType()).isEqualTo(mediaType);
        var expected = payload.replace("%2F", "/").replace("%2B", "+");
        assertThat(Base64.getDecoder().decode(image.base64()))
            .containsExactly(Base64.getDecoder().decode(expected));
        verifyNoInteractions(exchangeFunction);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "data:image/png;base64",
        "data:image/png,private-image-payload",
        "data:image/png;base64,",
        "data:image/png;base64,private-image-payload!",
        "data:image/png;base64,%ZZprivate-image-payload",
        "data:invalid-type;base64,aW1n",
        "data:text/html;base64,aW1n",
        "data:image/*;base64,aW1n"
    })
    void shouldRejectInvalidDataUrlWithoutExposingPayload(String url) {
        assertThatThrownBy(() -> generate(GeneratedFile.url(url, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("图片 Data URL")
            .hasMessageNotContaining("private-image-payload")
            .hasNoCause();
        verifyNoInteractions(exchangeFunction);
    }

    @Test
    void shouldKeepRawBase64Support() {
        var payload = "/9j/4AAQSkZJRgABAQ==";
        var image = generate(GeneratedFile.base64(payload, null));

        assertThat(image.base64()).isEqualTo(payload);
        assertThat(image.mediaType()).isEqualTo("image/jpeg");
        verifyNoInteractions(exchangeFunction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void shouldKeepRemoteDownload(String scheme) {
        var payload = "/9j/4AAQSkZJRgABAQ==";
        var buffer = DefaultDataBufferFactory.sharedInstance.wrap(
            Base64.getDecoder().decode(payload));
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(
            ClientResponse.create(HttpStatus.OK).body(Flux.just(buffer)).build()));

        // 使用公网 IP 字面量跳过 DNS，HTTP 交换由 mock 提供，不访问外部服务。
        var image = generate(GeneratedFile.url(scheme + "://192.0.2.1/cover", null));

        assertThat(image.base64()).isEqualTo(payload);
        assertThat(image.mediaType()).isEqualTo("image/jpeg");
        verify(exchangeFunction).exchange(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///cover.png", "ftp://192.0.2.1/cover.png",
        "http://127.0.0.1/cover.png", "https://192.168.1.1/cover.png"})
    void shouldKeepRemoteAddressRestrictions(String url) {
        assertThatThrownBy(() -> generate(GeneratedFile.url(url, null)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchangeFunction);
    }

    @Test
    void shouldCompressDataUrlBeforeReturningTheGeneratedImage() throws Exception {
        settings.setPngCompressionMode(PngCompressionMode.WHITE_BACKGROUND);
        var source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 0, 0x80ff0000);
        var output = new ByteArrayOutputStream();
        ImageIO.write(source, "PNG", output);
        var payload = Base64.getEncoder().encodeToString(output.toByteArray());
        when(imageModel.generateImage(any(GenerateImageRequest.class))).thenReturn(Mono.just(
            GenerateImageResult.builder().images(List.of(
                GeneratedFile.url("data:image/png;base64," + payload, null))).build()));
        var stages = new java.util.ArrayList<CoverGenerationTask.Stage>();
        var image = service.generate(settings, "标题", "风格", "正文", stages::add).block();
        assertThat(stages).containsExactly(CoverGenerationTask.Stage.DESCRIBING,
            CoverGenerationTask.Stage.GENERATING, CoverGenerationTask.Stage.COMPRESSING);
        var actual = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.base64())));
        assertThat(actual.getRGB(0, 0)).isEqualTo(0xffffffff);
        assertThat(actual.getRGB(1, 0)).isEqualTo(0xffff7f7f);
        assertThat(actual.getColorModel().hasAlpha()).isFalse();
        verifyNoInteractions(exchangeFunction);
    }

    private GeneratedImage generate(GeneratedFile file) {
        when(imageModel.generateImage(any(GenerateImageRequest.class))).thenReturn(Mono.just(
            GenerateImageResult.builder().images(List.of(file)).build()));
        return service.generate(settings, "标题", "风格", "正文", ignored -> { })
            .block(Duration.ofSeconds(5));
    }
}
