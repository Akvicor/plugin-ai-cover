package com.akvicor.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.akvicor.aicover.service.CoverGenerationTask;

/**
 * 文章列表提交的后台封面任务。
 *
 * @param postName 文章 metadata.name
 * @param mode 补充封面或重新生成
 */
@Schema(description = "在后台生成并设置文章封面")
public record BackgroundCoverRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "文章 metadata.name")
    String postName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "FILL 补充封面：文章已有封面时跳过；REPLACE 重新生成：直接替换封面")
    CoverGenerationTask.Mode mode
) {
}
