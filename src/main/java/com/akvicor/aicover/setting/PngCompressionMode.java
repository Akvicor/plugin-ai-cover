package com.akvicor.aicover.setting;

/** PNG 独立压缩策略；旧配置通过 CoverSettings 的默认值保持原图。 */
public enum PngCompressionMode {
    /** 保留模型返回的图片。 */
    NONE,
    /** 先以白底合成透明区域，再进行 256 色量化。 */
    WHITE_BACKGROUND,
    /** 移除透明通道，保留原 RGB 后进行 256 色量化。 */
    REMOVE_ALPHA
}
