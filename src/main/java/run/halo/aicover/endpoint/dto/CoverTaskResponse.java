package run.halo.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 封面图生成任务状态响应。
 *
 * @param taskId 任务 ID
 * @param status 任务状态：RUNNING、SUCCEEDED 或 FAILED
 * @param base64 生成成功时的图片内容 base64 编码
 * @param mediaType 生成成功时的图片媒体类型
 * @param message 生成失败时的错误提示
 */
@Schema(description = "封面图生成任务状态")
public record CoverTaskResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "任务 ID")
    String taskId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "任务状态：RUNNING、SUCCEEDED 或 FAILED")
    String status,
    @Schema(description = "生成成功时的图片内容 base64 编码") String base64,
    @Schema(description = "生成成功时的图片媒体类型") String mediaType,
    @Schema(description = "生成失败时的错误提示") String message
) {
}
