package com.akvicor.aicover.image.png;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 使用独立解码器与 PNG 块校验验证索引编码的像素、CRC 和行格式。 */
class IndexedPngEncoderTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 16, 256})
    void shouldRoundTripPaletteAndPixels(int colorCount) throws Exception {
        int[] colors = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            colors[i] = 0xff000000 | i << 16 | (255 - i) << 8 | i / 2;
        }
        var model = new IndexColorModel(8, colors.length, colors, 0, false, -1,
            DataBuffer.TYPE_BYTE);
        var source = new BufferedImage(31, 17, BufferedImage.TYPE_BYTE_INDEXED, model);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.getRaster().setSample(x, y, 0, (x + y * source.getWidth()) % colorCount);
            }
        }

        byte[] encoded = IndexedPngEncoder.encode(source);
        var decoded = ImageIO.read(new ByteArrayInputStream(encoded));
        assertThat(decoded.getWidth()).isEqualTo(source.getWidth());
        assertThat(decoded.getHeight()).isEqualTo(source.getHeight());
        assertThat(decoded.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()))
            .containsExactly(source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0,
                source.getWidth()));

        var chunks = readChunks(encoded);
        assertThat(chunks.keySet()).containsExactly("IHDR", "PLTE", "IDAT", "IEND");
        try (var rows = new InflaterInputStream(new ByteArrayInputStream(chunks.get("IDAT")))) {
            for (int y = 0; y < source.getHeight(); y++) {
                // None 保持索引序列的可压缩性，防止重新引入真彩色自适应过滤。
                assertThat(rows.read()).isZero();
                byte[] indices = rows.readNBytes(source.getWidth());
                assertThat(indices.length).isEqualTo(source.getWidth());
                for (int x = 0; x < indices.length; x++) {
                    assertThat(Byte.toUnsignedInt(indices[x]))
                        .isEqualTo(source.getRaster().getSample(x, y, 0));
                }
            }
            assertThat(rows.read()).isEqualTo(-1);
        }
    }

    @Test
    void shouldOmitTransparencyChunkForOpaqueImages() throws Exception {
        var source = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_INDEXED);
        byte[] encoded = IndexedPngEncoder.encode(source);
        assertThat(readChunks(encoded).keySet()).containsExactly("IHDR", "PLTE", "IDAT", "IEND");
        assertThat(ImageIO.read(new ByteArrayInputStream(encoded)).getColorModel().hasAlpha()).isFalse();
    }

    private Map<String, byte[]> readChunks(byte[] encoded) throws Exception {
        var chunks = new LinkedHashMap<String, byte[]>();
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            assertThat(input.readLong()).isEqualTo(0x89504e470d0a1a0aL);
            while (input.available() > 0) {
                int length = input.readInt();
                byte[] type = input.readNBytes(4);
                byte[] content = input.readNBytes(length);
                assertThat(content.length).isEqualTo(length);
                var crc = new CRC32();
                crc.update(type);
                crc.update(content);
                assertThat(Integer.toUnsignedLong(input.readInt())).isEqualTo(crc.getValue());
                chunks.put(new String(type, StandardCharsets.US_ASCII), content);
            }
        }
        assertThat(chunks.get("IEND")).isEmpty();
        return chunks;
    }
}
