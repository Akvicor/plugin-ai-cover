import { axiosInstance, coreApiClient, type Post } from '@halo-dev/api-client'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import type { AxiosAdapter } from 'axios'
import { defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCoverSaveGuard } from '../use-cover-save-guard'

describe('编辑器保存封面合并', () => {
  const postUrl = '/apis/content.halo.run/v1alpha1/posts/post'
  const originalAdapter = axiosInstance.defaults.adapter
  const name = ref<string | undefined>('post')
  const cover = ref('/old.png')
  let wrapper: VueWrapper | undefined
  let guard: ReturnType<typeof useCoverSaveGuard>
  let enabled = true
  const adapter = vi.fn<AxiosAdapter>(async (config) => ({
    data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  }))

  function post(value = '/old.png', postName = 'post') {
    return {
      apiVersion: 'content.halo.run/v1alpha1',
      kind: 'Post',
      metadata: { name: postName, version: 7 },
      spec: { cover: value, title: '修改后的标题', slug: 'post' },
    } as Post
  }

  function latest(value: string) {
    return { data: post(value) } as Awaited<ReturnType<typeof coreApiClient.content.post.getPost>>
  }

  beforeEach(() => {
    name.value = 'post'
    cover.value = '/old.png'
    enabled = true
    adapter.mockClear()
    axiosInstance.defaults.adapter = adapter
    vi.spyOn(coreApiClient.content.post, 'getPost').mockResolvedValue(latest('/background.png'))
    wrapper = mount(
      defineComponent({
        setup() {
          guard = useCoverSaveGuard(
            name,
            () => cover.value,
            (value) => {
              cover.value = value
            },
            () => enabled,
          )
          return () => h('div')
        },
      }),
    )
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    axiosInstance.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it.each([false, true])('保存旧编辑器时合并后台封面，序列化请求=%s', async (serialized) => {
    const body = post()
    const response = await axiosInstance.put(postUrl, serialized ? JSON.stringify(body) : body)
    expect(response.data.spec).toEqual({ ...body.spec, cover: '/background.png' })
    expect(response.data.metadata.version).toBe(7)
    expect(cover.value).toBe('/background.png')
    expect(coreApiClient.content.post.getPost).toHaveBeenCalledWith({ name: 'post' })
  })

  it('保留用户主动修改的封面，成功保存后更新比较基线', async () => {
    cover.value = '/manual.png'
    const first = await axiosInstance.put(postUrl, post('/manual.png'))
    expect(first.data.spec.cover).toBe('/manual.png')
    expect(coreApiClient.content.post.getPost).not.toHaveBeenCalled()
    const second = await axiosInstance.put(postUrl, post('/manual.png'))
    expect(second.data.spec.cover).toBe('/background.png')
    expect(cover.value).toBe('/background.png')
  })

  it('通过宿主生成的文章客户端保存时同样合并封面', async () => {
    const response = await coreApiClient.content.post.updatePost({ name: 'post', post: post() })
    expect(response.data.spec.cover).toBe('/background.png')
    expect(cover.value).toBe('/background.png')
  })

  it('同步服务端清空封面的操作', async () => {
    vi.mocked(coreApiClient.content.post.getPost).mockResolvedValue(latest(''))
    const response = await axiosInstance.put(postUrl, post())
    expect(response.data.spec.cover).toBe('')
    expect(cover.value).toBe('')
  })

  it('保持其他文章请求独立', async () => {
    const response = await axiosInstance.put(
      '/apis/content.halo.run/v1alpha1/posts/other',
      post('/other.png', 'other'),
    )
    expect(response.data.spec.cover).toBe('/other.png')
    expect(cover.value).toBe('/old.png')
    expect(coreApiClient.content.post.getPost).not.toHaveBeenCalled()
  })

  it('读取期间新编辑的封面保持在本地表单中', async () => {
    let resolve!: (value: ReturnType<typeof latest>) => void
    vi.mocked(coreApiClient.content.post.getPost).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const saving = axiosInstance.put(postUrl, post())
    await flushPromises()
    cover.value = '/new-unsaved.png'
    resolve(latest('/background.png'))
    await saving
    expect(cover.value).toBe('/new-unsaved.png')
  })

  it('读取服务端失败时终止旧封面写入', async () => {
    vi.mocked(coreApiClient.content.post.getPost).mockRejectedValue(new Error('read failed'))
    await expect(axiosInstance.put(postUrl, post())).rejects.toThrow('read failed')
    expect(adapter).not.toHaveBeenCalled()
    expect(cover.value).toBe('/old.png')
  })

  it('后台完成事件按当前服务端值同步', async () => {
    expect(await guard.refresh()).toBe(true)
    expect(cover.value).toBe('/background.png')
    cover.value = '/manual.png'
    expect(await guard.refresh()).toBe(false)
    expect(cover.value).toBe('/manual.png')
  })

  it('组件销毁后移除保存拦截', async () => {
    wrapper?.unmount()
    wrapper = undefined
    const response = await axiosInstance.put(postUrl, post())
    expect(response.data.spec.cover).toBe('/old.png')
    expect(coreApiClient.content.post.getPost).not.toHaveBeenCalled()
  })

  it('权限不满足时保持请求原样', async () => {
    enabled = false
    const response = await axiosInstance.put(postUrl, post())
    expect(response.data.spec.cover).toBe('/old.png')
    expect(coreApiClient.content.post.getPost).not.toHaveBeenCalled()
  })

  it('其他接口即使携带 Post 数据也保持原样', async () => {
    const response = await axiosInstance.put('/other-api', post())
    expect(response.data.spec.cover).toBe('/old.png')
    expect(coreApiClient.content.post.getPost).not.toHaveBeenCalled()
  })
})
