package com.akvicor.aicover.setting;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

/**
 * 插件设置项，对应 Setting 资源中 group 为 {@code default} 的表单数据。
 */
@Data
public class CoverSettings {

    /** 新安装和缺少并发设置时采用的任务并发上限。 */
    public static final int DEFAULT_MAX_CONCURRENT_GENERATIONS = 8;

    private int maxConcurrentGenerations = DEFAULT_MAX_CONCURRENT_GENERATIONS;

    /** 校验配置边界，拒绝小数及超出 1–1024 的并发值。 */
    public static int requireConcurrency(Number value) {
        int limit = value.intValue();
        if (limit < 1 || limit > 1024 || value.doubleValue() != limit) {
            throw new IllegalArgumentException("全局并发上限必须是 1–1024 之间的整数");
        }
        return limit;
    }

    /** 配置缺失或显式为 null 时保持默认并发值。 */
    @JsonSetter(nulls = Nulls.SKIP)
    public void setMaxConcurrentGenerations(Number value) {
        this.maxConcurrentGenerations = requireConcurrency(value);
    }

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

    /** JPEG 独立压缩设置，缺省保持原图。 */
    @JsonSetter(nulls = Nulls.SKIP)
    private QualityCompressionMode jpegCompressionMode = QualityCompressionMode.NONE;

    /** JPEG 重新编码的质量，范围为 1–100。 */
    private int jpegQuality = 80;

    /** WebP 独立压缩设置，缺省保持原图。 */
    @JsonSetter(nulls = Nulls.SKIP)
    private QualityCompressionMode webpCompressionMode = QualityCompressionMode.NONE;

    /** WebP 颜色重新编码的质量，透明通道保持原精度。 */
    private int webpQuality = 80;

    /** 校验 JPEG 质量；配置未提供或为 null 时使用默认值。 */
    @JsonSetter(nulls = Nulls.SKIP)
    public void setJpegQuality(Number value) {
        this.jpegQuality = requireQuality(value);
    }

    /** 校验 WebP 质量；配置未提供或为 null 时使用默认值。 */
    @JsonSetter(nulls = Nulls.SKIP)
    public void setWebpQuality(Number value) {
        this.webpQuality = requireQuality(value);
    }

    private static int requireQuality(Number value) {
        int quality = value.intValue();
        if (quality < 1 || quality > 100 || value.doubleValue() != quality) {
            throw new IllegalArgumentException("图片质量必须是 1–100 之间的整数");
        }
        return quality;
    }

    /** 上传封面图时默认使用的附件存储策略 metadata.name。 */
    private String policyName;

    /** 上传封面图时默认使用的附件分组 metadata.name，可为空。 */
    private String groupName;
}
