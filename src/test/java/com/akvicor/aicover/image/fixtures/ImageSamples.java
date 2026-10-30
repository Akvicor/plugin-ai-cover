package com.akvicor.aicover.image.fixtures;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPImageReaderSpi;
import com.luciad.imageio.webp.WebPImageWriterSpi;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 创建覆盖颜色纹理与透明渐变的测试图片，独立于正式压缩流程编码输入样本。 */
public final class ImageSamples {
    private ImageSamples() {
    }

    /** 生成含多种频率细节的画布，透明样本同时包含透明、半透明和不透明区域。 */
    public static BufferedImage scene(boolean transparent) {
        var image = new BufferedImage(320, 180,
            transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = transparent ? Math.clamp((x - 32) * 2, 0, 255) : 255;
                int red = (x * 3 + y * 2 + random.nextInt(64)) & 255;
                int green = (x + y * 5 + random.nextInt(64)) & 255;
                int blue = (x * 11 + y * 7 + random.nextInt(64)) & 255;
                image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    /** 用 JDK JPEG 编码器准备指定质量的原始输入。 */
    public static byte[] jpeg(BufferedImage image, int quality) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality / 100f);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 准备无损或有损 WebP 输入，用于验证压缩收益及已压缩图片的保留。 */
    public static byte[] webp(BufferedImage image, int quality, boolean lossless) throws IOException {
        var writer = new WebPImageWriterSpi().createWriterInstance();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = (WebPWriteParam) writer.getDefaultWriteParam();
            params.setCompressionType(lossless ? CompressionType.Lossless : CompressionType.Lossy);
            params.setCompressionQuality(quality / 100f);
            params.setAlphaQuality(100);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 独立解码 WebP 输出，检查尺寸及透明通道。 */
    public static BufferedImage readWebp(byte[] bytes) throws IOException {
        var reader = new WebPImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }
}
