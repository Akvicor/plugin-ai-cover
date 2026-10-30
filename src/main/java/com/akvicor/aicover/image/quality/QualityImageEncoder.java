package com.akvicor.aicover.image.quality;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPImageReaderSpi;
import com.luciad.imageio.webp.WebPImageWriterSpi;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 在内存中对生成的 JPEG/WebP 按质量重新编码，保持格式和像素尺寸。 */
public final class QualityImageEncoder {
    /** 两种使用质量参数的生成图片格式。 */
    public enum Format {
        JPEG,
        WEBP
    }

    private QualityImageEncoder() {
    }

    /** 每次调用独立创建并释放编解码器；WebP 通过显式 SPI 加载，避免注册全局插件。 */
    public static byte[] encode(byte[] original, Format format, int quality) throws IOException {
        try {
            var reader = switch (format) {
                case JPEG -> ImageIO.getImageReadersByFormatName("JPEG").next();
                case WEBP -> new WebPImageReaderSpi().createReaderInstance();
            };
            BufferedImage image;
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
                reader.setInput(input);
                image = reader.read(0);
            } finally {
                reader.dispose();
            }

            var writer = switch (format) {
                case JPEG -> ImageIO.getImageWritersByFormatName("JPEG").next();
                case WEBP -> new WebPImageWriterSpi().createWriterInstance();
            };
            var encoded = new ByteArrayOutputStream();
            try (var output = new MemoryCacheImageOutputStream(encoded)) {
                writer.setOutput(output);
                var parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (format == Format.JPEG) {
                    // 复用宿主 JPEG writer；JDK 参数额外支持无损的 Huffman 表优化。
                    if (parameters instanceof JPEGImageWriteParam jpeg) {
                        jpeg.setOptimizeHuffmanTables(true);
                    }
                } else {
                    var webp = (WebPWriteParam) parameters;
                    webp.setCompressionType(CompressionType.Lossy);
                    webp.setAlphaQuality(100);
                    // 提升插画中彩色细线和边缘的 RGB→YUV 转换质量。
                    webp.setUseSharpYUV(true);
                }
                parameters.setCompressionQuality(quality / 100f);
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally {
                writer.dispose();
            }
            return encoded.toByteArray();
        } catch (LinkageError error) {
            // 将原生库加载失败转为正常任务错误，避免致命错误使任务停留在运行中。
            throw new IllegalStateException("图片编解码器加载失败，请检查运行环境或关闭该格式的压缩", error);
        }
    }
}
