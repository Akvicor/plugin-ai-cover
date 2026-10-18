package run.halo.aicover.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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

    private final AttachmentService attachmentService;

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
        // 存储文件名和创建前的显示名称都采用用户输入，保持附件命名语义一致。
        var filePart = new SimpleFilePart(displayName, file.content(),
            parseMediaType(file.headers().getFirst(HttpHeaders.CONTENT_TYPE)));
        return attachmentService.upload(username, policyName, trimToNull(groupName), filePart,
            attachment -> {
                if (attachment.getSpec() == null) {
                    attachment.setSpec(new Attachment.AttachmentSpec());
                }
                attachment.getSpec().setDisplayName(displayName);
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
