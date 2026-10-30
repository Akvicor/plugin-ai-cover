import { describe, expect, it } from 'vitest'
import { buildDefaultFileName, toImageFile } from './cover'

describe('图片文件转换', () => {
  it.each([
    ['自定义封面.jpeg', '自定义封面.jpg'],
    ['自定义封面.jpg', '自定义封面.jpg'],
    ['自定义封面.JPEG', '自定义封面.jpg'],
    ['自定义封面.JPG', '自定义封面.jpg'],
    ['Cover.Jpg', 'Cover.jpg'],
    ['Cover.JpEg', 'Cover.jpg'],
    ['Cover.PNG', 'Cover.png'],
    ['Cover.PnG', 'Cover.png'],
    ['Cover.WEBP', 'Cover.webp'],
    ['Cover.WebP', 'Cover.webp'],
    ['封面.jpeg.png', '封面.jpeg.png'],
  ])('将 %s 规范为 %s，并保留二进制内容和媒体类型', async (name, expectedName) => {
    const bytes = [0, 255, 128, 66]
    const file = toImageFile(btoa(String.fromCharCode(...bytes)), name, 'image/jpeg')
    const content = await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as ArrayBuffer)
      reader.onerror = () => reject(reader.error)
      reader.readAsArrayBuffer(file)
    })

    expect([...new Uint8Array(content)]).toEqual(bytes)
    expect(file.name).toBe(expectedName)
    expect(file.type).toBe('image/jpeg')
  })

  it.each(['image/jpeg', 'image/jpg', 'IMAGE/JPEG', 'IMAGE/JPG'])(
    '%s 的默认附件名使用 jpg 后缀',
    (mediaType) => {
      expect(buildDefaultFileName('文章标题', mediaType)).toBe('文章标题-00.jpg')
    },
  )

  it('未提供媒体类型时沿用图片默认类型', () => {
    const file = toImageFile(btoa('image'), '封面.png')

    expect(file.type).toBe('image/png')
  })
})
