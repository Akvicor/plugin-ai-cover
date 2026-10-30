package com.akvicor.aicover.image.png;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import com.akvicor.aicover.service.GeneratedImage;
import com.akvicor.aicover.setting.PngCompressionMode;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 验证真实 PNG 编解码、透明边缘、调色板及独立格式开关。 */
class PngCompressorTest {
    private final PngCompressor compressor = new PngCompressor();

    @Test
    void disabledShouldPreserveTheOriginalObjectWithoutDecoding() {
        var original = new GeneratedImage("original-payload", "image/png");
        var onStart = mock(Runnable.class);
        assertThat(compressor.compress(original, PngCompressionMode.NONE, onStart).block()).isSameAs(original);
        verifyNoInteractions(onStart);
    }

    @Test
    void removeAlphaModeShouldKeepOriginalRgbIncludingHiddenColors() throws Exception {
        int[] colors = {0x00ff0000, 0x40ff0000, 0x80ff0000, 0xc0ff0000, 0xffff0000, 0xff00ff00};
        var source = new BufferedImage(colors.length, 3, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            source.setRGB(0, y, colors.length, 1, colors, 0, colors.length);
        }
        var actual = compress(source, PngCompressionMode.REMOVE_ALPHA);
        for (int x = 0; x < colors.length; x++) {
            assertThat(actual.getRGB(x, 0)).isEqualTo(colors[x] | 0xff000000);
        }
        assertThat(actual.getColorModel()).isInstanceOf(IndexColorModel.class);
        assertThat(actual.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    void whiteModeShouldCompositePartialTransparencyBeforeQuantizing() throws Exception {
        var source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00000000);
        source.setRGB(1, 0, 0x80ff0000);
        source.setRGB(2, 0, 0xff0000ff);
        var actual = compress(source, PngCompressionMode.WHITE_BACKGROUND);
        assertThat(actual.getColorModel().hasAlpha()).isFalse();
        assertThat(actual.getRGB(0, 0)).isEqualTo(0xffffffff);
        assertThat(actual.getRGB(1, 0)).isEqualTo(0xffff7f7f);
        assertThat(actual.getRGB(2, 0)).isEqualTo(0xff0000ff);
    }

    @ParameterizedTest
    @EnumSource(value = PngCompressionMode.class, names = {"WHITE_BACKGROUND", "REMOVE_ALPHA"})
    void shouldQuantizeGradientsWithoutChangingDimensions(PngCompressionMode mode) throws Exception {
        var source = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int alpha = x < 64 ? 0 : x < 128 ? x : 255;
                source.setRGB(x, y, alpha << 24 | x << 16 | y * 2 << 8 | 255 - x);
            }
        }
        var actual = compress(source, mode);
        assertThat(actual.getWidth()).isEqualTo(source.getWidth());
        assertThat(actual.getHeight()).isEqualTo(source.getHeight());
        assertThat(actual.getColorModel()).isInstanceOf(IndexColorModel.class);
        assertThat(((IndexColorModel) actual.getColorModel()).getMapSize()).isLessThanOrEqualTo(256);
        double absoluteError = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int before = source.getRGB(x, y);
                int after = actual.getRGB(x, y);
                assertThat(after >>> 24).isEqualTo(255);
                for (int shift : new int[] {16, 8, 0}) {
                    double expected = mode == PngCompressionMode.WHITE_BACKGROUND
                        ? onWhite(before, shift) : before >>> shift & 255;
                    absoluteError += Math.abs(expected - (after >>> shift & 255));
                }
            }
        }
        // 平滑色块应仍保持接近原图的合成颜色，防止固定调色板或 Alpha 处理退化。
        assertThat(absoluteError / (source.getWidth() * source.getHeight() * 3)).isLessThan(6);
    }

    @Test
    void shouldRejectBrokenPngInsteadOfReturningAnUncompressedSuccess() {
        var invalid = new GeneratedImage(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
            "image/png");
        assertThatThrownBy(() -> compressor.compress(invalid, PngCompressionMode.WHITE_BACKGROUND, () -> { })
            .block(Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("PNG 图片压缩失败");
    }

    private BufferedImage compress(BufferedImage source, PngCompressionMode mode) throws Exception {
        var original = new ByteArrayOutputStream();
        ImageIO.write(source, "PNG", original);
        var generated = new GeneratedImage(Base64.getEncoder().encodeToString(original.toByteArray()),
            "image/png");
        var onStart = mock(Runnable.class);
        var result = compressor.compress(generated, mode, onStart).block(Duration.ofSeconds(30));
        verify(onStart).run();
        assertThat(result.mediaType()).isEqualTo("image/png");
        byte[] bytes = Base64.getDecoder().decode(result.base64());
        // IHDR 的颜色类型和交错字段直接验证最终附件编码，而非中间 BufferedImage。
        assertThat(bytes[25]).isEqualTo((byte) 3);
        assertThat(bytes[28]).isZero();
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private double onWhite(int argb, int shift) {
        double alpha = (argb >>> 24) / 255.0;
        return (argb >>> shift & 255) * alpha + 255 * (1 - alpha);
    }
}
