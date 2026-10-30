package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.akvicor.aicover.image.png.PngCompressor;
import com.akvicor.aicover.setting.CoverSettings;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.exception.ProviderApiException;
import run.halo.aifoundation.image.GenerateImageRequest;
import run.halo.aifoundation.image.GenerateImageResult;
import run.halo.aifoundation.image.ImageGenerationModel;
import run.halo.aifoundation.media.GeneratedFile;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/** 验证两个阶段各最多两次 SDK 调用，空响应重试及阶段之间的独立性。 */
class CoverGenerationRetryTest {
    private final LanguageModel language = mock(LanguageModel.class);
    private final ImageGenerationModel images = mock(ImageGenerationModel.class);
    private final ExtensionGetter extensions = mock(ExtensionGetter.class);
    private final AiModelService models = mock(AiModelService.class);
    private final CoverSettings settings = new CoverSettings();
    private final CoverImageService service = new CoverImageService(extensions, null, new PngCompressor());

    @BeforeEach
    void configure() {
        settings.setDescriptionModelName("language");
        settings.setImageModelName("image");
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(models));
        when(models.languageModel("language")).thenReturn(Mono.just(language));
        when(models.imageGenerationModel("image")).thenReturn(Mono.just(images));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t"})
    void shouldRetryEmptyTextAndEmptyImageOnceEach(String empty) {
        when(language.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.just(GenerateTextResult.builder().text(empty).build()))
            .thenReturn(text());
        when(images.generateImage(any(GenerateImageRequest.class)))
            .thenReturn(Mono.just(GenerateImageResult.builder().images(List.of()).build()))
            .thenReturn(image());
        assertThat(generate().base64()).isEqualTo("aW1n");
        var textRequests = ArgumentCaptor.forClass(GenerateTextRequest.class);
        var imageRequests = ArgumentCaptor.forClass(GenerateImageRequest.class);
        verify(language, times(2)).generateText(textRequests.capture());
        verify(images, times(2)).generateImage(imageRequests.capture());
        assertThat(textRequests.getAllValues()).allSatisfy(request -> assertThat(request.getMaxRetries()).isZero());
        assertThat(imageRequests.getAllValues()).allSatisfy(request -> {
            assertThat(request.getMaxRetries()).isZero();
            assertThat(request.getPrompt()).contains("有效的画面描述");
        });
    }

    @Test
    void shouldStopAfterTwoEmptyTextResponses() {
        when(language.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.empty());
        assertThatThrownBy(this::generate).hasMessageContaining("语言模型没有返回画面描述");
        verify(language, times(2)).generateText(any(GenerateTextRequest.class));
        verifyNoInteractions(images);
    }

    @Test
    void shouldRetryImageFailureWithoutRepeatingSuccessfulText() {
        when(language.generateText(any(GenerateTextRequest.class))).thenReturn(text());
        when(images.generateImage(any(GenerateImageRequest.class)))
            .thenReturn(Mono.error(new ProviderApiException("provider", 503, "busy")))
            .thenReturn(image());
        assertThat(generate()).isNotNull();
        verify(language).generateText(any(GenerateTextRequest.class));
        verify(images, times(2)).generateImage(any(GenerateImageRequest.class));
    }

    @Test
    void shouldStopAfterTwoImageFailures() {
        when(language.generateText(any(GenerateTextRequest.class))).thenReturn(text());
        when(images.generateImage(any(GenerateImageRequest.class)))
            .thenReturn(Mono.error(new ProviderApiException("provider", 503, "busy")));
        assertThatThrownBy(this::generate).isInstanceOf(ProviderApiException.class);
        verify(language).generateText(any(GenerateTextRequest.class));
        verify(images, times(2)).generateImage(any(GenerateImageRequest.class));
    }

    @Test
    void shouldNotRetryAuthenticationErrors() {
        when(language.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.error(new ProviderApiException("provider", 401, "unauthorized")));
        assertThatThrownBy(this::generate).isInstanceOf(ProviderApiException.class);
        verify(language).generateText(any(GenerateTextRequest.class));
        verifyNoInteractions(images);
    }

    @Test
    void localImageProcessingErrorsShouldNotTriggerAnotherModelCall() {
        when(language.generateText(any(GenerateTextRequest.class))).thenReturn(text());
        when(images.generateImage(any(GenerateImageRequest.class))).thenReturn(Mono.just(
            GenerateImageResult.builder().images(List.of(GeneratedFile.url("file:///cover.png", null))).build()));
        assertThatThrownBy(this::generate).hasMessageContaining("不支持的图片地址协议");
        verify(language).generateText(any(GenerateTextRequest.class));
        verify(images).generateImage(any(GenerateImageRequest.class));
    }

    private GeneratedImage generate() {
        return service.generate(settings, "标题", "风格", "正文", ignored -> { }).block();
    }

    private Mono<GenerateTextResult> text() {
        return Mono.just(GenerateTextResult.builder().text("有效的画面描述").build());
    }

    private Mono<GenerateImageResult> image() {
        return Mono.just(GenerateImageResult.builder().images(List.of(GeneratedFile.base64("aW1n", "image/png"))).build());
    }
}
