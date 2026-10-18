package run.halo.aicover.service;

/**
 * 生成后的图片数据，统一以 base64 表示，便于前端预览与二次上传。
 *
 * @param base64 图片内容的 base64 编码
 * @param mediaType 图片媒体类型，例如 image/png
 */
public record GeneratedImage(String base64, String mediaType) {
}
