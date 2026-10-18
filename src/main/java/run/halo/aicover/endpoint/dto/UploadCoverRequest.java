package run.halo.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;

/**
 * 封面图的 multipart 上传请求。
 *
 * @param file 图片文件，媒体类型由文件 part 的请求头提供
 * @param displayName 附件显示名称
 * @param policyName 存储策略 metadata.name
 * @param groupName 分组 metadata.name，可为空
 */
@Schema(description = "上传封面图请求")
public record UploadCoverRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "图片文件",
        type = "string", format = "binary", implementation = String.class)
    FilePart file,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "附件显示名称")
    String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "存储策略 metadata.name")
    String policyName,
    @Schema(description = "分组 metadata.name，可为空") String groupName
) {

    /**
     * 解析文件与文本字段，文件内容交给附件服务消费，避免聚合成大段 JSON。
     */
    public static UploadCoverRequest fromParts(MultiValueMap<String, Part> parts) {
        if (!(parts.getFirst("file") instanceof FilePart file)) {
            throw new ServerWebInputException("file 必须为图片文件");
        }
        var displayName = textField(parts, "displayName");
        var policyName = textField(parts, "policyName");
        if (!StringUtils.hasText(displayName) || !StringUtils.hasText(policyName)) {
            throw new ServerWebInputException("displayName、policyName 均不能为空");
        }
        return new UploadCoverRequest(file, displayName, policyName, textField(parts, "groupName"));
    }

    private static String textField(MultiValueMap<String, Part> parts, String name) {
        var part = parts.getFirst(name);
        if (part == null) {
            return null;
        }
        if (part instanceof FormFieldPart field) {
            return field.value();
        }
        throw new ServerWebInputException(name + " 必须为文本字段");
    }
}
