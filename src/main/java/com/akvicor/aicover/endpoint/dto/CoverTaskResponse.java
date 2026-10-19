package com.akvicor.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import com.akvicor.aicover.service.CoverGenerationTask;
import run.halo.app.core.extension.attachment.Attachment;

/**
 * 封面图生成任务状态响应。
 *
 * @param taskId 任务 ID
 * @param status 运行、成功、失败、跳过或取消
 * @param base64 编辑器任务生成的图片内容 base64 编码，仅详情接口返回
 * @param mediaType 图片媒体类型
 * @param message 失败、跳过或取消时的说明
 * @param postName 任务关联的文章标识
 * @param title 本次生成使用的标题
 * @param stage 当前实际处理阶段
 * @param source 触发来源
 * @param mode 后台任务写入封面的方式，编辑器任务为空
 * @param imageAvailable 服务端是否仍保留可预览的图片
 * @param attachment 后台任务设置为封面的附件
 * @param createdAt 任务创建时间
 * @param finishedAt 任务结束时间
 */
@Schema(description = "封面图生成任务状态")
public record CoverTaskResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "任务 ID")
    String taskId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "任务状态：RUNNING、SUCCEEDED、FAILED、SKIPPED 或 CANCELLED")
    String status,
    @Schema(description = "编辑器任务生成的图片内容 base64 编码，仅详情接口返回") String base64,
    @Schema(description = "图片媒体类型") String mediaType,
    @Schema(description = "失败、跳过或取消时的说明") String message,
    @Schema(description = "文章 metadata.name") String postName,
    @Schema(description = "生成使用的文章标题") String title,
    @Schema(description = "处理阶段：QUEUED、DESCRIBING、GENERATING、UPLOADING、APPLYING、COMPLETED")
    String stage,
    @Schema(description = "触发来源：EDITOR、LIST 或 PUBLISH") String source,
    @Schema(description = "后台任务写入方式：FILL 补充封面，REPLACE 重新生成") String mode,
    @Schema(description = "服务端是否仍保留可预览的图片") boolean imageAvailable,
    @Schema(description = "后台任务设置为封面的附件") Attachment attachment,
    @Schema(description = "任务创建时间") Instant createdAt,
    @Schema(description = "任务结束时间") Instant finishedAt
) {

    /** 统一构造详情与摘要，列表摘要省略图片数据。 */
    public static CoverTaskResponse from(CoverGenerationTask task, boolean includeImage) {
        var state = task.snapshot(includeImage);
        return new CoverTaskResponse(state.id(), state.status().name(), state.base64(),
            state.mediaType(), state.message(), state.postName(), state.title(), state.stage().name(),
            state.source().name(), state.mode() == null ? null : state.mode().name(),
            state.imageAvailable(), state.attachment(), state.createdAt(), state.finishedAt());
    }
}
