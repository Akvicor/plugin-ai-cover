package run.halo.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 封面生成默认配置响应。
 *
 * @param title 文章标题，由后端根据 postName 读取
 * @param prompt 默认提示词
 * @param policyName 默认存储策略 metadata.name
 * @param groupName 默认分组 metadata.name
 */
@Schema(description = "封面生成默认配置")
public record CoverConfigResponse(
    @Schema(description = "文章标题") String title,
    @Schema(description = "默认提示词") String prompt,
    @Schema(description = "默认存储策略 metadata.name") String policyName,
    @Schema(description = "默认分组 metadata.name") String groupName
) {
}
