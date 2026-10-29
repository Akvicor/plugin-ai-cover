package com.akvicor.aicover.setting;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

/**
 * 插件设置项，对应 Setting 资源中 group 为 {@code default} 的表单数据。
 */
@Data
public class CoverSettings {

    /** 开启后，在文章发布成功且缺少封面时自动生成并保存封面。 */
    private boolean autoGenerateOnPublish;

    /** 生成封面图时使用的默认提示词（风格与构图约束）。 */
    private String prompt;

    /**
     * 第一步用于理解文章、生成画面描述的语言模型 AiModel.metadata.name。
     */
    private String descriptionModelName;

    /**
     * 第二步用于出图的图片生成模型 AiModel.metadata.name。
     */
    private String imageModelName;

    /** 图片宽高比，例如 16:9；为空时不指定。 */
    private String aspectRatio;

    /** PNG 的独立压缩方式；缺少配置时保持模型原图。 */
    @JsonSetter(nulls = Nulls.SKIP)
    private PngCompressionMode pngCompressionMode = PngCompressionMode.NONE;

    /** 上传封面图时默认使用的附件存储策略 metadata.name。 */
    private String policyName;

    /** 上传封面图时默认使用的附件分组 metadata.name，可为空。 */
    private String groupName;
}
