import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { coverApi } from '@/api'
import CoverGenerateModal from '@/components/CoverGenerateModal.vue'

const { route } = vi.hoisted(() => ({
  route: { query: { name: undefined as string | undefined } },
}))

vi.mock('vue-router', () => ({ useRoute: () => route }))
vi.mock('@/api', () => ({
  coverApi: {
    getConfig: vi.fn<typeof coverApi.getConfig>(),
    generate: vi.fn<typeof coverApi.generate>(),
    getGenerateTask: vi.fn<typeof coverApi.getGenerateTask>(),
  },
}))
vi.mock('@halo-dev/components', () => ({
  Toast: { warning: vi.fn<(message: string) => void>() },
  VModal: { template: '<section><slot /><slot name="footer" /></section>' },
  VButton: {
    props: ['loading', 'disabled'],
    template: '<button :disabled="loading || disabled"><slot /></button>',
  },
  VLoading: { template: '<span />' },
}))
vi.mock('@/components/CoverUploadModal.vue', () => ({ default: { template: '<div />' } }))

describe('生成前的文章校验', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.resetAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    route.query.name = 'post-name'
    vi.mocked(coverApi.getConfig).mockResolvedValue({ title: '后台标题', prompt: '默认风格' })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.restoreAllMocks()
  })

  async function openModal() {
    wrapper = mount(CoverGenerateModal)
    await flushPromises()
    return wrapper
  }

  it.each([undefined, '', ' \t'])('自动取得的标识为 %s 时阻止生成请求', async (name) => {
    route.query.name = name
    const modal = await openModal()

    await modal.get('button').trigger('click')

    expect(coverApi.generate).not.toHaveBeenCalled()
    expect(modal.get('.text-red-700').text()).not.toBe('')
    expect(modal.get('input').element.value).toBe('后台标题')
    expect(modal.get('textarea').element.value).toBe('默认风格')
    expect(modal.get('button').element.disabled).toBe(false)
  })

  it('展示后台校验错误并保留输入和可重试状态', async () => {
    const detail = '文章标题和正文均为空，请先保存文章内容后再生成'
    vi.mocked(coverApi.generate).mockRejectedValue({
      isAxiosError: true,
      response: { data: { detail } },
    })
    const modal = await openModal()
    await modal.get('input').setValue('用户覆盖标题')
    await modal.get('textarea').setValue('用户风格')

    await modal.get('button').trigger('click')
    await flushPromises()

    expect(coverApi.generate).toHaveBeenCalledWith({
      postName: 'post-name',
      title: '用户覆盖标题',
      prompt: '用户风格',
    })
    expect(modal.get('.text-red-700').text()).toBe(detail)
    expect(modal.get('input').element.value).toBe('用户覆盖标题')
    expect(modal.get('textarea').element.value).toBe('用户风格')
    expect(modal.get('button').element.disabled).toBe(false)
    expect(coverApi.getGenerateTask).not.toHaveBeenCalled()
  })

  it('校验失败后重新提交有效文章可以继续生成', async () => {
    vi.mocked(coverApi.generate)
      .mockRejectedValueOnce({ isAxiosError: true, response: { data: { detail: '文章尚未保存' } } })
      .mockResolvedValueOnce({ taskId: 'task-id', status: 'RUNNING' })
    vi.mocked(coverApi.getGenerateTask).mockResolvedValue({
      taskId: 'task-id',
      status: 'SUCCEEDED',
      base64: 'aW1n',
      mediaType: 'image/png',
    })
    const modal = await openModal()
    await modal.get('button').trigger('click')
    await flushPromises()
    expect(modal.find('.text-red-700').exists()).toBe(true)

    await modal.get('button').trigger('click')
    await flushPromises()

    expect(modal.find('.text-red-700').exists()).toBe(false)
    expect(modal.get('img[alt="生成的封面图"]').attributes('src')).toBe(
      'data:image/png;base64,aW1n',
    )
    expect(coverApi.getGenerateTask).toHaveBeenCalledWith('task-id')
    expect(modal.get('button').element.disabled).toBe(false)
  })

  it('请求异常缺少有效错误详情时仍显示提示并恢复按钮', async () => {
    vi.mocked(coverApi.generate).mockRejectedValue(new Error('network unavailable'))
    const modal = await openModal()

    await modal.get('button').trigger('click')
    await flushPromises()

    expect(modal.get('.text-red-700').text()).not.toBe('')
    expect(modal.get('button').element.disabled).toBe(false)
    expect(coverApi.getGenerateTask).not.toHaveBeenCalled()
  })
})
