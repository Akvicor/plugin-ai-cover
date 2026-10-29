package com.akvicor.aicover.image.png;

import com.akvicor.aicover.service.GeneratedImage;
import com.akvicor.aicover.setting.PngCompressionMode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 将生成的静态 PNG 转成自适应索引色图片，预览和附件共用同一结果。 */
@Component
public class PngCompressor {
    /** 关闭时保留原图；启用后在工作线程完成图片解码、量化及内存编码。 */
    public Mono<GeneratedImage> compress(GeneratedImage image, PngCompressionMode mode) {
        if (mode == PngCompressionMode.NONE || !"image/png".equalsIgnoreCase(image.mediaType())) {
            return Mono.just(image);
        }
        return Mono.fromCallable(() -> compressPng(image, mode))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(IOException.class,
                error -> new IllegalStateException("PNG 图片压缩失败", error));
    }

    private GeneratedImage compressPng(GeneratedImage image, PngCompressionMode mode)
        throws IOException {
        byte[] bytes = Base64.getDecoder().decode(image.base64());
        BufferedImage source;
        var reader = ImageIO.getImageReadersByFormatName("PNG").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input);
            source = reader.read(0);
        } finally {
            reader.dispose();
        }
        if (mode == PngCompressionMode.WHITE_BACKGROUND) {
            var opaque = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
            var graphics = opaque.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            source = opaque;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        if (mode == PngCompressionMode.REMOVE_ALPHA) {
            // 去透明时使用像素原始 RGB，原本隐藏的颜色会重新显示。
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] |= 0xff000000;
            }
        }
        var indexed = ErrorDiffusion.apply(pixels, width, height, PaletteQuantizer.quantize(pixels));
        return new GeneratedImage(Base64.getEncoder().encodeToString(IndexedPngEncoder.encode(indexed)),
            "image/png");
    }
}
