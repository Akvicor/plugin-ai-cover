package com.akvicor.aicover.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.akvicor.aicover.image.fixtures.ImageSamples;
import com.akvicor.aicover.image.png.PngCompressor;
import com.akvicor.aicover.service.GeneratedImage;
import com.akvicor.aicover.setting.CoverSettings;
import com.akvicor.aicover.setting.PngCompressionMode;
import com.akvicor.aicover.setting.QualityCompressionMode;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证 AI 图片按格式独立处理、原格式输出、压缩收益、透明度及并发隔离。 */
class GeneratedImageCompressorTest {
    private final GeneratedImageCompressor compressor = new GeneratedImageCompressor(new PngCompressor());

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml"})
    void disabledShouldKeepOriginalBytesWithoutDecoding(String mediaType) {
        var input = new GeneratedImage("original-payload", mediaType);
        var onStart = mock(Runnable.class);
        assertThat(compressor.compress(input, new CoverSettings(), onStart).block()).isSameAs(input);
        verifyNoInteractions(onStart);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/webp"})
    void eachFormatShouldUseOnlyItsOwnSetting(String mediaType) {
        var settings = enabled();
        settings.setPngCompressionMode(PngCompressionMode.WHITE_BACKGROUND);
        switch (mediaType) {
            case "image/png" -> settings.setPngCompressionMode(PngCompressionMode.NONE);
            case "image/jpeg" -> settings.setJpegCompressionMode(QualityCompressionMode.NONE);
            case "image/webp" -> settings.setWebpCompressionMode(QualityCompressionMode.NONE);
            default -> throw new AssertionError(mediaType);
        }
        var input = new GeneratedImage("original-payload", mediaType);
        var onStart = mock(Runnable.class);
        assertThat(compressor.compress(input, settings, onStart).block()).isSameAs(input);
        verifyNoInteractions(onStart);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/svg+xml"})
    void otherFormatsShouldKeepExistingPassthroughBehavior(String mediaType) {
        var input = new GeneratedImage("original-payload", mediaType);
        var settings = enabled();
        settings.setPngCompressionMode(PngCompressionMode.WHITE_BACKGROUND);
        var onStart = mock(Runnable.class);
        assertThat(compressor.compress(input, settings, onStart).block()).isSameAs(input);
        verifyNoInteractions(onStart);
    }

    @Test
    void jpegShouldStayJpegAndKeepDimensions() throws Exception {
        var source = ImageSamples.scene(false);
        byte[] bytes = ImageSamples.jpeg(source, 100);
        var onStart = mock(Runnable.class);
        var result = compressor.compress(image(bytes, "image/jpeg"), enabled(), onStart)
            .block(Duration.ofSeconds(20));
        byte[] output = Base64.getDecoder().decode(result.base64());
        assertThat(result.mediaType()).isEqualTo("image/jpeg");
        assertThat(output.length).isLessThan(bytes.length);
        assertThat(output[0]).isEqualTo((byte) 0xff);
        assertThat(output[1]).isEqualTo((byte) 0xd8);
        var decoded = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(decoded.getWidth()).isEqualTo(source.getWidth());
        assertThat(decoded.getHeight()).isEqualTo(source.getHeight());
        verify(onStart).run();
    }

    @Test
    void webpShouldKeepEveryAlphaValueAndDimensions() throws Exception {
        var source = ImageSamples.scene(true);
        byte[] bytes = ImageSamples.webp(source, 100, true);
        var result = compressor.compress(image(bytes, "image/webp"), enabled(), () -> { })
            .block(Duration.ofSeconds(20));
        byte[] output = Base64.getDecoder().decode(result.base64());
        assertThat(result.mediaType()).isEqualTo("image/webp");
        assertThat(output.length).isLessThan(bytes.length);
        var before = ImageSamples.readWebp(bytes);
        var after = ImageSamples.readWebp(output);
        assertThat(after.getWidth()).isEqualTo(before.getWidth());
        assertThat(after.getHeight()).isEqualTo(before.getHeight());
        for (int y = 0; y < before.getHeight(); y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                assertThat(after.getRGB(x, y) >>> 24).isEqualTo(before.getRGB(x, y) >>> 24);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "webp"})
    void qualityShouldControlTheEncoding(String format) throws Exception {
        var source = ImageSamples.scene(false);
        byte[] bytes = format.equals("jpeg") ? ImageSamples.jpeg(source, 100)
            : ImageSamples.webp(source, 100, true);
        var low = enabled();
        low.setJpegQuality(20);
        low.setWebpQuality(20);
        var high = enabled();
        high.setJpegQuality(90);
        high.setWebpQuality(90);
        var input = image(bytes, "image/" + format);
        var small = compressor.compress(input, low, () -> { }).block(Duration.ofSeconds(20));
        var large = compressor.compress(input, high, () -> { }).block(Duration.ofSeconds(20));
        assertThat(Base64.getDecoder().decode(small.base64()).length)
            .isLessThan(Base64.getDecoder().decode(large.base64()).length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "webp"})
    void shouldKeepOriginalWhenReencodingWouldIncreaseSize(String format) throws Exception {
        var source = ImageSamples.scene(false);
        byte[] bytes = format.equals("jpeg") ? ImageSamples.jpeg(source, 5)
            : ImageSamples.webp(source, 5, false);
        var settings = enabled();
        settings.setJpegQuality(100);
        settings.setWebpQuality(100);
        var input = image(bytes, "image/" + format);
        assertThat(compressor.compress(input, settings, () -> { }).block(Duration.ofSeconds(20)))
            .isSameAs(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/webp"})
    void shouldReportInvalidImageAsProcessingFailure(String mediaType) {
        assertThatThrownBy(() -> compressor.compress(image(new byte[] {1, 2, 3}, mediaType),
            enabled(), () -> { }).block(Duration.ofSeconds(20)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("图片压缩失败");
    }

    @Test
    void concurrentFormatsShouldMatchTheirSequentialResults() throws Exception {
        var jpeg = image(ImageSamples.jpeg(ImageSamples.scene(false), 100), "image/jpeg");
        var webp = image(ImageSamples.webp(ImageSamples.scene(true), 100, true), "image/webp");
        var settings = enabled();
        var expectedJpeg = compressor.compress(jpeg, settings, () -> { }).block();
        var expectedWebp = compressor.compress(webp, settings, () -> { }).block();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<GeneratedImage>>();
            for (int i = 0; i < 12; i++) {
                var input = i % 2 == 0 ? jpeg : webp;
                results.add(executor.submit(() -> compressor.compress(input, settings, () -> { })
                    .block(Duration.ofSeconds(20))));
            }
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get(25, TimeUnit.SECONDS))
                    .isEqualTo(i % 2 == 0 ? expectedJpeg : expectedWebp);
            }
        }
    }

    private static CoverSettings enabled() {
        var settings = new CoverSettings();
        settings.setJpegCompressionMode(QualityCompressionMode.QUALITY);
        settings.setWebpCompressionMode(QualityCompressionMode.QUALITY);
        return settings;
    }

    private static GeneratedImage image(byte[] bytes, String mediaType) {
        return new GeneratedImage(Base64.getEncoder().encodeToString(bytes), mediaType);
    }
}
