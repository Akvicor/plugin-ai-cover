package com.akvicor.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 当前实例内各文章最新任务的摘要，供文章列表与编辑器窗口共用一份状态。
 *
 * @param tasks 任务摘要，不含图片数据
 */
@Schema(description = "封面任务状态列表")
public record CoverTaskListResponse(
    @ArraySchema(schema = @Schema(implementation = CoverTaskResponse.class),
        arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "各文章最新任务的摘要"))
    List<CoverTaskResponse> tasks
) {
}
