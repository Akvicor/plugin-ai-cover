package com.akvicor.aicover.service;

import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;

/**
 * 将生成好的封面图上传到附件库，并在创建前写入用户指定的显示名称。
 */
@Component
@RequiredArgsConstructor
public class CoverAttachmentService {

    /** 只规范三种图片格式的末尾后缀，保留文件名主体的大小写。 */
    private static final Pattern IMAGE_SUFFIX = Pattern.compile("\\.(jpe?g|png|webp)$", Pattern.CASE_INSENSITIVE);

    private final AttachmentService attachmentService;

    /** 将后台生成的图片保存为附件，命名与手动上传的默认规则一致。 */
    public Mono<Attachment> uploadGenerated(String username, String policyName, String groupName,
        String title, GeneratedImage image) {
        return Mono.defer(() -> {
            var mediaType = parseMediaType(image.mediaType());
            var extension = switch (mediaType.getSubtype().toLowerCase(Locale.ROOT)) {
                case "jpeg", "jpg" -> "jpg";
                case "webp" -> "webp";
                case "gif" -> "gif";
                case "bmp" -> "bmp";
                default -> "png";
            };
            var base = (title == null ? "" : title)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "-")
                .replaceAll("[\\s\\p{Z}\\uFEFF]+", " ").strip();
            base = base.isEmpty() ? "cover" : base.substring(0, Math.min(base.length(), 100));
            var name = base + "-00." + extension;
            var bytes = Base64.getDecoder().decode(image.base64());
            var file = new SimpleFilePart(name,
                Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))),
                mediaType);
            return upload(username, policyName, groupName, name, file);
        });
    }

    /**
     * 上传封面图。
     *
     * @param username 附件所有者用户名
     * @param policyName 存储策略 metadata.name
     * @param groupName 分组 metadata.name，可为空
     * @param displayName 附件显示名称，例如 文章标题-00.png
     * @param file 图片文件，内容通过 multipart 文件数据传递
     * @return 创建完成的附件
     */
    public Mono<Attachment> upload(String username, String policyName, String groupName,
        String displayName, FilePart file) {
        // 存储文件名与附件显示名共用小写后缀，JPEG 与 JPG 统一为 jpg。
        var normalizedName = IMAGE_SUFFIX.matcher(displayName).replaceFirst(match -> {
            var suffix = match.group().toLowerCase(Locale.ROOT);
            return suffix.equals(".jpeg") ? ".jpg" : suffix;
        });
        var filePart = new SimpleFilePart(normalizedName, file.content(),
            parseMediaType(file.headers().getFirst(HttpHeaders.CONTENT_TYPE)));
        return attachmentService.upload(username, policyName, trimToNull(groupName), filePart,
            attachment -> {
                if (attachment.getSpec() == null) {
                    attachment.setSpec(new Attachment.AttachmentSpec());
                }
                attachment.getSpec().setDisplayName(normalizedName);
            });
    }

    private static MediaType parseMediaType(String mediaType) {
        if (!StringUtils.hasText(mediaType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mediaType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.strip();
    }
}
