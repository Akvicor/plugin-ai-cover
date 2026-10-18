import { describe, expect, it } from 'vitest'
import { toImageFile } from './cover'

describe('图片文件转换', () => {
  it('保留二进制内容、自定义完整名称和媒体类型', async () => {
    const bytes = [0, 255, 128, 66]
    const file = toImageFile(btoa(String.fromCharCode(...bytes)), '自定义封面.jpg', 'image/jpeg')
    const content = await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as ArrayBuffer)
      reader.onerror = () => reject(reader.error)
      reader.readAsArrayBuffer(file)
    })

    expect([...new Uint8Array(content)]).toEqual(bytes)
    expect(file.name).toBe('自定义封面.jpg')
    expect(file.type).toBe('image/jpeg')
  })

  it('未提供媒体类型时沿用图片默认类型', () => {
    const file = toImageFile(btoa('image'), '封面.png')

    expect(file.type).toBe('image/png')
  })
})
