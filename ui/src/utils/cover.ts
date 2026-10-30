/** 媒体类型到文件扩展名的映射。 */
const EXTENSION_BY_MEDIA_TYPE: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'image/bmp': 'bmp',
}

/**
 * 生成附件默认名称，格式为 {@code 文章标题-00.扩展名}。
 *
 * <p>扩展名根据图片实际媒体类型推导，避免把 JPEG 命名为 .png。
 *
 * @param title 文章标题
 * @param mediaType 图片媒体类型，例如 image/png
 */
export function buildDefaultFileName(title: string, mediaType?: string): string {
  const base = sanitizeFileName(title) || 'cover'
  const extension = EXTENSION_BY_MEDIA_TYPE[(mediaType || '').toLowerCase()] ?? 'png'
  return `${base}-00.${extension}`
}

function sanitizeFileName(value: string): string {
  return value
    .replace(/[\\/:*?"<>|\r\n\t]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 100)
}

/**
 * 将生成的图片转换为可直接用于 img src 的 data URL。
 */
export function toDataUrl(base64: string, mediaType?: string): string {
  return `data:${mediaType || 'image/png'};base64,${base64}`
}

/** 将预览图片转换为二进制文件，后缀统一为 .jpg、.png 或 .webp，保留名称主体与媒体类型。 */
export function toImageFile(base64: string, fileName: string, mediaType?: string): File {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index)
  }
  const normalizedName = fileName.replace(/\.(jpe?g|png|webp)$/i, (extension) => {
    const suffix = extension.toLowerCase()
    return suffix === '.jpeg' ? '.jpg' : suffix
  })
  return new File([bytes], normalizedName, { type: mediaType || 'image/png' })
}
