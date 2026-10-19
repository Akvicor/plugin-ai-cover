package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import com.akvicor.aicover.setting.CoverSettingService;
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

/**
 * 验证「先理解、再生成」两步调用：语言模型先阅读文章产出画面描述，
 * 图片模型再按该描述出图。
 */
@ExtendWith(MockitoExtension.class)
class CoverImageServiceTest {

    @Mock
    ExtensionGetter extensionGetter;

    @Mock
    CoverSettingService coverSettingService;

    @Mock
    AiModelService aiModelService;

    @Mock
    LanguageModel languageModel;

    @Mock
    ImageGenerationModel imageGenerationModel;

    CoverImageService coverImageService;

    @BeforeEach
    void setUp() {
        coverImageService = new CoverImageService(extensionGetter, coverSettingService, null);
    }

    @Test
    void shouldDescribeArticleThenGenerateImage() {
        var settings = new CoverSettings();
        settings.setPrompt("二次元插画风格");
        settings.setDescriptionModelName("lang-model");
        settings.setImageModelName("image-model");
        settings.setAspectRatio("16:9");

        when(coverSettingService.getSettings()).thenReturn(Mono.just(settings));
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(aiModelService));
        when(aiModelService.languageModel("lang-model")).thenReturn(Mono.just(languageModel));
        when(aiModelService.imageGenerationModel("image-model"))
            .thenReturn(Mono.just(imageGenerationModel));
        when(languageModel.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.just(GenerateTextResult.builder()
                .text("银发少女站在发光的服务器机房中，四周漂浮着全息终端窗口。")
                .build()));
        when(imageGenerationModel.generateImage(any(GenerateImageRequest.class)))
            .thenReturn(Mono.just(GenerateImageResult.builder()
                .images(List.of(GeneratedFile.base64("aW1n", "image/png")))
                .build()));

        var stages = new ArrayList<CoverGenerationTask.Stage>();
        var image = coverImageService.generate("Caddy 安装", "二次元插画风格", "apt install caddy",
                stages::add)
            .block();
        assertThat(stages).containsExactly(CoverGenerationTask.Stage.DESCRIBING,
            CoverGenerationTask.Stage.GENERATING);

        // 第一步：语言模型收到的是文章内容
        var textRequest = ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(languageModel).generateText(textRequest.capture());
        assertThat(textRequest.getValue().getPrompt())
            .contains("Caddy 安装")
            .contains("apt install caddy");

        // 第二步：图片模型收到的是提示词 + 语言模型产出的画面描述
        var imageRequest = ArgumentCaptor.forClass(GenerateImageRequest.class);
        verify(imageGenerationModel).generateImage(imageRequest.capture());
        assertThat(imageRequest.getValue().getPrompt())
            .contains("二次元插画风格")
            .contains("银发少女站在发光的服务器机房中");
        assertThat(imageRequest.getValue().getAspectRatio()).isEqualTo("16:9");

        assertThat(image).isNotNull();
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.base64()).isEqualTo("aW1n");
    }

    @Test
    void shouldFailWhenLanguageModelReturnsBlankText() {
        var settings = new CoverSettings();
        settings.setPrompt("风格");
        settings.setDescriptionModelName("lang-model");
        settings.setImageModelName("image-model");

        when(coverSettingService.getSettings()).thenReturn(Mono.just(settings));
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(aiModelService));
        when(aiModelService.languageModel("lang-model")).thenReturn(Mono.just(languageModel));
        when(languageModel.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.just(GenerateTextResult.builder().text("   ").build()));

        assertThat(coverImageService.generate("标题", "风格", "正文", ignored -> { })
            .onErrorResume(error -> Mono.empty())
            .block()).isNull();

        // 语言模型未产出描述时不应调用图片模型
        verify(imageGenerationModel, org.mockito.Mockito.never())
            .generateImage(any(GenerateImageRequest.class));
    }

    @Test
    void shouldRequireDescriptionModel() {
        var settings = new CoverSettings();
        settings.setPrompt("风格");

        when(coverSettingService.getSettings()).thenReturn(Mono.just(settings));

        var image = coverImageService.generate("标题", "风格", "正文", ignored -> { })
            .onErrorResume(e -> Mono.empty())
            .block();
        assertThat(image).isNull();
    }

    @Test
    void shouldReportMissingDescriptionModel() {
        var settings = new CoverSettings();
        settings.setPrompt("风格");
        settings.setImageModelName("image-model");
        // descriptionModelName 为空

        when(coverSettingService.getSettings()).thenReturn(Mono.just(settings));

        var message = coverImageService.generate("标题", "风格", "正文", ignored -> { })
            .map(image -> "不应生成成功")
            .onErrorResume(error -> Mono.just(error.getMessage()))
            .block();

        assertThat(message).contains("未配置文章理解模型");
        // 未配置语言模型时不应继续调用模型服务
        verify(aiModelService, org.mockito.Mockito.never()).languageModel(any());
    }
}
