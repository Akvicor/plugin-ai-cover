import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Component } from 'vue'
import type { coverApi as CoverApi, CoverTaskResponse } from '@/api'

const coverApi = vi.hoisted(() => ({
  getConfig: vi.fn<typeof CoverApi.getConfig>(),
  generate: vi.fn<typeof CoverApi.generate>(),
  getGenerateTask: vi.fn<typeof CoverApi.getGenerateTask>(),
  listTasks: vi.fn<typeof CoverApi.listTasks>(),
}))
vi.mock('@/api', () => ({ coverApi }))
vi.mock('@halo-dev/components', () => ({
  Toast: { warning: vi.fn<(message: string) => void>() },
  VModal: { template: '<section><slot /><slot name="footer" /></section>' },
  VButton: {
    props: ['loading', 'disabled'],
    template: '<button :disabled="loading || disabled"><slot /></button>',
  },
  VLoading: { template: '<span />' },
}))
vi.mock('@/components/CoverUploadModal.vue', () => ({
  default: { props: ['taskId'], template: '<div data-testid="upload" :data-task="taskId" />' },
}))

function editorTask(overrides: Partial<CoverTaskResponse> = {}): CoverTaskResponse {
  return {
    taskId: 'task-id',
    postName: 'post-name',
    status: 'SUCCEEDED',
    source: 'EDITOR',
    ...overrides,
  }
}

describe('编辑器生成窗口', () => {
  let CoverGenerateModal: Component
  let wrapper: VueWrapper | undefined

  beforeEach(async () => {
    vi.resetAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    // 任务状态是模块级共享缓存，每个用例重新加载窗口模块。
    vi.resetModules()
    CoverGenerateModal = (await import('@/components/CoverGenerateModal.vue')).default
    coverApi.getConfig.mockResolvedValue({ title: '后台标题', prompt: '默认风格' })
    coverApi.listTasks.mockResolvedValue({ tasks: [] })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  async function openModal(postName?: string) {
    wrapper = mount(CoverGenerateModal, { props: { postName } })
    await flushPromises()
    return wrapper
  }

  function button(text: string) {
    return wrapper!.findAll('button').find((item) => item.text() === text)!
  }

  it.each([undefined, '', ' \t'])('文章标识为 %s 时阻止生成请求', async (name) => {
    const modal = await openModal(name)
    await button('生成').trigger('click')

    expect(coverApi.generate).not.toHaveBeenCalled()
    expect(modal.get('.text-red-700').text()).not.toBe('')
    expect(modal.get('input').element.value).toBe('后台标题')
    expect(modal.get('textarea').element.value).toBe('默认风格')
  })

  it.each([
    ['后台校验错误', '文章标题和正文均为空，请先保存文章内容后再生成'],
    ['文章已锁定', '该文章正在生成封面'],
  ])('提交被拒绝（%s）时显示原因并保留输入', async (_, detail) => {
    coverApi.generate.mockRejectedValue({ isAxiosError: true, response: { data: { detail } } })
    const modal = await openModal('post-name')
    await modal.get('input').setValue('用户覆盖标题')
    await modal.get('textarea').setValue('用户风格')

    await button('生成').trigger('click')
    await flushPromises()

    expect(coverApi.generate).toHaveBeenCalledWith({
      postName: 'post-name',
      title: '用户覆盖标题',
      prompt: '用户风格',
    })
    expect(modal.get('.text-red-700').text()).toBe(detail)
    expect(modal.get('input').element.value).toBe('用户覆盖标题')
    expect(button('生成').element.disabled).toBe(false)
  })

  it('请求异常缺少错误详情时仍显示提示并恢复按钮', async () => {
    coverApi.generate.mockRejectedValue(new Error('network unavailable'))
    const modal = await openModal('post-name')
    await button('生成').trigger('click')
    await flushPromises()
    expect(modal.get('.text-red-700').text()).toBe('提交生成请求失败，请稍后重试')
    expect(button('生成').element.disabled).toBe(false)
  })

  it('生成完成后预览图片并把任务 ID 交给上传窗口', async () => {
    coverApi.generate.mockResolvedValue(editorTask({ status: 'RUNNING' }))
    coverApi.getGenerateTask.mockResolvedValue(
      editorTask({ base64: 'aW1n', mediaType: 'image/png' }),
    )
    const modal = await openModal('post-name')
    coverApi.listTasks.mockResolvedValue({ tasks: [editorTask({ imageAvailable: true })] })

    await button('生成').trigger('click')
    await flushPromises()

    expect(modal.get('img[alt="生成的封面图"]').attributes('src')).toBe(
      'data:image/png;base64,aW1n',
    )
    expect(coverApi.getGenerateTask).toHaveBeenCalledWith('task-id')
    await button('上传').trigger('click')
    expect(modal.get('[data-testid="upload"]').attributes('data-task')).toBe('task-id')
  })

  it('其他入口正在生成时显示进度，生成按钮不可用', async () => {
    coverApi.listTasks.mockResolvedValue({
      tasks: [editorTask({ status: 'RUNNING', source: 'LIST', stage: 'UPLOADING' })],
    })
    const modal = await openModal('post-name')

    expect(modal.get('[role="status"]').text()).toBe('正在上传附件')
    expect(modal.text()).toContain('该文章正在生成封面')
    expect(button('生成中').element.disabled).toBe(true)
    await button('生成中').trigger('click')
    expect(coverApi.generate).not.toHaveBeenCalled()
  })

  it('只在任务进行中定时查询，结束后停止', async () => {
    vi.useFakeTimers()
    coverApi.listTasks
      .mockResolvedValueOnce({ tasks: [editorTask({ status: 'RUNNING', title: '先前的标题' })] })
      .mockResolvedValue({ tasks: [editorTask({ status: 'FAILED', message: '模型不可用' })] })
    const modal = await openModal('post-name')
    expect(modal.get('input').element.value).toBe('先前的标题')

    await vi.advanceTimersByTimeAsync(5000)
    expect(modal.get('.text-red-700').text()).toBe('模型不可用')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(coverApi.listTasks).toHaveBeenCalledTimes(2)
  })

  it('列表或发布任务设置封面后展示该封面并通知编辑器同步', async () => {
    const attachment = {
      apiVersion: 'storage.halo.run/v1alpha1',
      kind: 'Attachment',
      metadata: { name: 'cover' },
      spec: { displayName: '封面.png' },
      status: { permalink: '/uploads/saved.png' },
    }
    coverApi.listTasks.mockResolvedValue({
      tasks: [editorTask({ source: 'PUBLISH', mode: 'FILL', attachment })],
    })
    const modal = await openModal('post-name')

    expect(modal.get('img[alt="生成的封面图"]').attributes('src')).toBe('/uploads/saved.png')
    expect(modal.emitted('applied')).toEqual([[attachment]])
    expect(button('封面已设置').element.disabled).toBe(true)
    expect(coverApi.getGenerateTask).not.toHaveBeenCalled()
  })

  it('图片已上传或过期时不再读取，也不提示错误', async () => {
    coverApi.listTasks.mockResolvedValue({ tasks: [editorTask({ imageAvailable: false })] })
    const modal = await openModal('post-name')
    expect(coverApi.getGenerateTask).not.toHaveBeenCalled()
    expect(modal.find('.text-red-700').exists()).toBe(false)
    expect(button('上传').element.disabled).toBe(true)
  })

  it('读取图片失败后刷新进度即可恢复预览，保留输入且不重新生成', async () => {
    coverApi.listTasks.mockResolvedValue({ tasks: [editorTask({ imageAvailable: true })] })
    coverApi.getGenerateTask
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(editorTask({ base64: 'aW1n' }))
    const modal = await openModal('post-name')
    expect(modal.get('.text-red-700').text()).toBe('读取图片失败，请刷新进度重试')
    await modal.get('input').setValue('用户保留的标题')

    await button('刷新进度').trigger('click')
    await flushPromises()

    expect(modal.get('img[alt="生成的封面图"]').attributes('src')).toBe(
      'data:image/png;base64,aW1n',
    )
    expect(modal.find('.text-red-700').exists()).toBe(false)
    expect(modal.get('input').element.value).toBe('用户保留的标题')
    expect(coverApi.generate).not.toHaveBeenCalled()
  })

  it('新任务的预览优先，迟到的旧图片不能覆盖当前结果', async () => {
    coverApi.listTasks.mockResolvedValue({
      tasks: [editorTask({ taskId: 'old-task', imageAvailable: true })],
    })
    let resolveOld!: (result: CoverTaskResponse) => void
    coverApi.getGenerateTask
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve
          }),
      )
      .mockResolvedValue(editorTask({ taskId: 'new-task', base64: 'bmV3' }))
    const modal = await openModal('post-name')
    coverApi.generate.mockResolvedValue(editorTask({ taskId: 'new-task', status: 'RUNNING' }))
    coverApi.listTasks.mockResolvedValue({
      tasks: [editorTask({ taskId: 'new-task', imageAvailable: true })],
    })

    await button('生成').trigger('click')
    await flushPromises()
    resolveOld(editorTask({ taskId: 'old-task', base64: 'b2xk' }))
    await flushPromises()

    expect(modal.get('img[alt="生成的封面图"]').attributes('src')).toBe(
      'data:image/png;base64,bmV3',
    )
  })
})
