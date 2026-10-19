package com.akvicor.aicover.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 生成封面图请求。
 *
 * @param postName 文章 metadata.name，后端据此读取最新保存的标题与正文
 * @param title 可选的标题覆盖值；为空时使用后端读取到的文章标题
 * @param prompt 可选的提示词；为空时使用插件设置中的默认提示词
 */
@Schema(description = "生成封面图请求")
public record GenerateCoverRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "文章 metadata.name，用于在后端读取标题与正文")
    String postName,
    @Schema(description = "可选的标题覆盖值，为空时使用文章标题") String title,
    @Schema(description = "可选提示词，为空时使用插件默认提示词") String prompt
) {
}
