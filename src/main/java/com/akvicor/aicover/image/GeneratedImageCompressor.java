package com.akvicor.aicover.image;

import com.akvicor.aicover.image.png.PngCompressor;
import com.akvicor.aicover.image.quality.QualityImageEncoder;
import com.akvicor.aicover.image.quality.QualityImageEncoder.Format;
import com.akvicor.aicover.service.GeneratedImage;
import com.akvicor.aicover.setting.CoverSettings;
import com.akvicor.aicover.setting.QualityCompressionMode;
import java.io.IOException;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 根据生成结果的实际格式选择独立压缩设置，预览和附件复用同一结果。 */
@Component
@RequiredArgsConstructor
public class GeneratedImageCompressor {
    private final PngCompressor pngCompressor;

    /** 压缩只发生在 AI 结果处理阶段；未启用对应格式时保留原图并跳过进度通知。 */
    public Mono<GeneratedImage> compress(GeneratedImage image, CoverSettings settings, Runnable onStart) {
        if ("image/png".equalsIgnoreCase(image.mediaType())) {
            return pngCompressor.compress(image, settings.getPngCompressionMode(), onStart);
        }
        final Format format;
        final QualityCompressionMode mode;
        final int quality;
        if ("image/jpeg".equalsIgnoreCase(image.mediaType())) {
            format = Format.JPEG;
            mode = settings.getJpegCompressionMode();
            quality = settings.getJpegQuality();
        } else if ("image/webp".equalsIgnoreCase(image.mediaType())) {
            format = Format.WEBP;
            mode = settings.getWebpCompressionMode();
            quality = settings.getWebpQuality();
        } else {
            return Mono.just(image);
        }
        if (mode == QualityCompressionMode.NONE) {
            return Mono.just(image);
        }
        return Mono.fromCallable(() -> {
                onStart.run();
                byte[] original = Base64.getDecoder().decode(image.base64());
                byte[] encoded = QualityImageEncoder.encode(original, format, quality);
                // 模型可能已经使用较低质量编码，保留更小的原图避免增大文件和再次损失细节。
                if (encoded.length >= original.length) {
                    return image;
                }
                return new GeneratedImage(Base64.getEncoder().encodeToString(encoded), image.mediaType());
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(IOException.class,
                error -> new IllegalStateException(format + " 图片压缩失败", error));
    }
}
