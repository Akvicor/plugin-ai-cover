import type { ListedPost } from '@halo-dev/api-client'
import type { OperationItem } from '@halo-dev/ui-shared'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, type Component } from 'vue'
import type { coverApi as CoverApi, CoverTaskResponse } from '@/api'

const { coverApi, invalidateQueries, warning } = vi.hoisted(() => ({
  coverApi: {
    listTasks: vi.fn<typeof CoverApi.listTasks>(),
    submitBackground: vi.fn<typeof CoverApi.submitBackground>(),
  },
  invalidateQueries: vi.fn<() => Promise<void>>(),
  warning: vi.fn<(message: string) => void>(),
}))
vi.mock('@/api', () => ({ coverApi }))
vi.mock('@halo-dev/ui-shared', () => ({ definePlugin: (value: unknown) => value }))
vi.mock('@/tiptap/cover-extension', () => ({ coverEditorExtension: {} }))
vi.mock('@halo-dev/components', () => ({
  Toast: { warning },
  IconImageAddLine: { template: '<svg />' },
  VDropdownItem: { template: '<button><slot /></button>' },
  VEntityField: { template: '<div><slot name="description" /></div>' },
  // 记录悬停内容，按真实指令的对象参数断言展示文本。
  vTooltip: {
    mounted: (el: HTMLElement, binding: { value: { content: string } }) => {
      el.dataset.tooltip = binding.value.content
    },
    updated: (el: HTMLElement, binding: { value: { content: string } }) => {
      el.dataset.tooltip = binding.value.content
    },
  },
}))

const attachment = {
  apiVersion: 'storage.halo.run/v1alpha1',
  kind: 'Attachment',
  metadata: { name: 'image' },
  spec: {},
  status: { permalink: '/cover.png' },
}

function task(overrides: Partial<CoverTaskResponse>): CoverTaskResponse {
  return { taskId: 'task', postName: 'post', status: 'RUNNING', source: 'LIST', ...overrides }
}

describe('文章列表封面菜单与状态徽标', () => {
  let CoverTaskField: Component
  const wrappers: VueWrapper[] = []

  beforeEach(async () => {
    vi.resetAllMocks()
    vi.useFakeTimers()
    // 状态缓存与已刷新记录均为模块级，每个用例重新加载组件模块。
    vi.resetModules()
    CoverTaskField = (await import('../CoverTaskField.vue')).default
    invalidateQueries.mockResolvedValue(undefined)
  })

  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers.length = 0
    vi.useRealTimers()
  })

  async function open(cover?: string) {
    const wrapper = mount(CoverTaskField, {
      props: { postName: 'post', cover },
      global: { provide: { VUE_QUERY_CLIENT: { invalidateQueries } } },
    })
    wrappers.push(wrapper)
    await flushPromises()
    return wrapper
  }

  function listed(cover?: string) {
    return ref({
      post: { metadata: { name: 'post' }, spec: { title: '文章', cover } },
    } as ListedPost)
  }

  it('菜单按封面状态显示补充或重新生成，并提交对应处理方式', async () => {
    const plugin = (await import('@/index')).default
    const create = plugin.extensionPoints!['post:list-item:operation:create']!
    coverApi.submitBackground.mockResolvedValue(task({}))
    coverApi.listTasks.mockResolvedValue({ tasks: [] })

    const fill = create(listed())[0] as OperationItem<ListedPost>
    const replace = create(listed('/old.png'))[0] as OperationItem<ListedPost>
    expect(fill.label).toBe('AI 补充封面')
    expect(replace.label).toBe('AI 生成封面')
    expect(fill.permissions).toEqual(['*'])

    await fill.action?.()
    await replace.action?.()
    expect(coverApi.submitBackground).toHaveBeenNthCalledWith(1, 'post', 'FILL')
    expect(coverApi.submitBackground).toHaveBeenNthCalledWith(2, 'post', 'REPLACE')

    const field = plugin.extensionPoints!['post:list-item:field:create']!(listed('/old.png'))[0]!
    expect(field.props).toEqual({ postName: 'post', cover: '/old.png' })
    expect(field.permissions).toEqual(['*'])
  })

  it('显示列表与发布任务的进行中状态，编辑器任务不显示', async () => {
    coverApi.listTasks
      .mockResolvedValueOnce({ tasks: [task({ stage: 'DESCRIBING', source: 'PUBLISH' })] })
      .mockResolvedValue({ tasks: [task({ source: 'EDITOR' })] })
    const running = await open()
    const status = running.get('[role="status"]')
    expect(status.attributes('data-status')).toBe('RUNNING')
    expect(status.attributes('aria-label')).toBe('正在理解文章')

    await vi.advanceTimersByTimeAsync(5000)
    expect(running.find('[role="status"]').exists()).toBe(false)
  })

  it('刷新后展示压缩阶段并继续跟踪上传', async () => {
    coverApi.listTasks
      .mockResolvedValueOnce({ tasks: [task({ stage: 'GENERATING' })] })
      .mockResolvedValueOnce({ tasks: [task({ stage: 'COMPRESSING' })] })
      .mockResolvedValue({ tasks: [task({ stage: 'UPLOADING' })] })
    const field = await open()
    expect(field.get('[role="status"]').attributes('aria-label')).toBe('正在生成图片')
    await vi.advanceTimersByTimeAsync(5000)
    expect(field.get('[role="status"]').attributes('aria-label')).toBe('正在压缩图片')
    await vi.advanceTimersByTimeAsync(5000)
    expect(field.get('[role="status"]').attributes('aria-label')).toBe('正在上传附件')
  })

  it('成功后显示结果并只刷新一次列表，行组件重建后不重复刷新', async () => {
    coverApi.listTasks.mockResolvedValue({
      tasks: [task({ status: 'SUCCEEDED', attachment, finishedAt: '2026-10-19T08:00:00Z' })],
    })
    const field = await open('/old.png')
    const status = field.get('[role="status"]')
    expect(status.attributes('data-status')).toBe('SUCCEEDED')
    expect(status.attributes('data-tooltip')).toContain('封面已设置')
    expect(status.attributes('data-tooltip')).toContain('结束于')
    expect(invalidateQueries).toHaveBeenCalledExactlyOnceWith(
      { queryKey: ['posts'] },
      { throwOnError: true },
    )

    field.unmount()
    wrappers.length = 0
    await open('/old.png')
    expect(invalidateQueries).toHaveBeenCalledOnce()
  })

  it('列表已显示新封面时不刷新', async () => {
    coverApi.listTasks.mockResolvedValue({ tasks: [task({ status: 'SUCCEEDED', attachment })] })
    await open('/cover.png')
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('失败时悬停显示原因', async () => {
    coverApi.listTasks.mockResolvedValue({
      tasks: [task({ status: 'FAILED', message: '模型调用超时，请重试或更换响应更快的模型' })],
    })
    const status = (await open()).get('[role="status"]')
    expect(status.attributes('data-status')).toBe('FAILED')
    expect(status.attributes('data-tooltip')).toContain('封面生成失败')
    expect(status.attributes('data-tooltip')).toContain('模型调用超时')
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('跳过只在本页看到任务结束时刷新一次列表', async () => {
    coverApi.listTasks.mockResolvedValue({
      tasks: [task({ status: 'SKIPPED', message: '文章已有封面，已跳过' })],
    })
    const loaded = await open()
    expect(loaded.get('[role="status"]').attributes('data-status')).toBe('SKIPPED')
    expect(invalidateQueries).not.toHaveBeenCalled()

    loaded.unmount()
    wrappers.length = 0
    vi.resetModules()
    CoverTaskField = (await import('../CoverTaskField.vue')).default
    coverApi.listTasks
      .mockResolvedValueOnce({ tasks: [task({})] })
      .mockResolvedValue({ tasks: [task({ status: 'SKIPPED', message: '文章已有封面，已跳过' })] })
    await open()
    await vi.advanceTimersByTimeAsync(5000)
    expect(invalidateQueries).toHaveBeenCalledOnce()
  })

  it('列表刷新失败时提示，下次状态变化时重试', async () => {
    invalidateQueries.mockRejectedValueOnce(new Error('network'))
    coverApi.listTasks.mockResolvedValue({ tasks: [task({ status: 'SUCCEEDED', attachment })] })
    const field = await open('/old.png')
    expect(warning).toHaveBeenCalledOnce()

    await field.setProps({ cover: '' })
    await flushPromises()
    expect(invalidateQueries).toHaveBeenCalledTimes(2)
  })
})
