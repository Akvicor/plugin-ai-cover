package com.akvicor.aicover.setting;

/** JPEG 与 WebP 各自选择的质量压缩策略。 */
public enum QualityCompressionMode {
    /** 保留模型返回的原始图片。 */
    NONE,
    /** 按指定质量重新编码，仅采用体积更小的结果。 */
    QUALITY
}
