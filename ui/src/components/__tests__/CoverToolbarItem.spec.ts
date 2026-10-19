import { coreApiClient } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import type { Editor } from '@halo-dev/richtext-editor'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { coverApi, type Attachment } from '@/api'
import CoverToolbarItem from '@/components/CoverToolbarItem.vue'

const permissionState = vi.hoisted(() => ({ permissions: undefined as string[] | undefined }))

vi.mock('vue-router', () => ({ useRoute: () => ({ query: { name: 'post-name' } }) }))
vi.mock('@/api', () => ({
  coverApi: {
    getConfig: vi.fn<typeof coverApi.getConfig>(),
    generate: vi.fn<typeof coverApi.generate>(),
    getGenerateTask: vi.fn<typeof coverApi.getGenerateTask>(),
    listTasks: vi.fn<typeof coverApi.listTasks>(),
    upload: vi.fn<typeof coverApi.upload>(),
  },
}))
vi.mock('@halo-dev/components', () => ({
  Toast: {
    warning: vi.fn<(message: string) => void>(),
    success: vi.fn<(message: string) => void>(),
  },
  VModal: { template: '<section><slot /><slot name="footer" /></section>' },
  VButton: {
    props: ['loading', 'disabled'],
    template: '<button :disabled="loading || disabled"><slot /></button>',
  },
  VLoading: { template: '<span />' },
}))
vi.mock('@halo-dev/richtext-editor', () => ({
  ToolbarItem: {
    props: ['action'],
    template: '<button @click="action">AI 生成封面</button>',
  },
}))
vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    attachment: { getUrl: (attachment: Attachment) => attachment.status?.permalink },
    permission: { getUserPermissions: () => permissionState.permissions },
  },
}))

const toolbarProps = { editor: {} as Editor, isActive: false }
const attachment: Attachment = {
  apiVersion: 'storage.halo.run/v1alpha1',
  kind: 'Attachment',
  metadata: { name: 'cover-attachment' },
  spec: { displayName: '文章标题-00.png' },
  status: { permalink: '/uploads/cover.png' },
}

function listResponse<T>(items: T[]) {
  return {
    data: {
      items,
      page: 1,
      size: 1000,
      total: items.length,
      totalPages: 1,
      first: true,
      last: true,
      hasNext: false,
      hasPrevious: false,
    },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

describe('上传后设置当前编辑器封面', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.resetAllMocks()
    permissionState.permissions = ['*']
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.spyOn(coreApiClient.storage.policy, 'listPolicy').mockResolvedValue(
      listResponse([
        {
          apiVersion: 'storage.halo.run/v1alpha1',
          kind: 'Policy',
          metadata: { name: 'default-policy' },
          spec: { templateName: 'local', displayName: '本地存储' },
        },
      ]),
    )
    vi.spyOn(coreApiClient.storage.group, 'listGroup').mockResolvedValue(listResponse([]))
    vi.mocked(coverApi.getConfig).mockResolvedValue({ title: '文章标题', prompt: '默认风格' })
    vi.mocked(coverApi.generate).mockResolvedValue({
      taskId: 'task-id',
      postName: 'post-name',
      status: 'RUNNING',
      source: 'EDITOR',
    })
    vi.mocked(coverApi.listTasks).mockImplementation(async () => ({
      tasks: vi.mocked(coverApi.generate).mock.calls.length
        ? [
            {
              taskId: 'task-id',
              postName: 'post-name',
              status: 'SUCCEEDED',
              source: 'EDITOR',
              imageAvailable: true,
            },
          ]
        : [],
    }))
    vi.mocked(coverApi.getGenerateTask).mockResolvedValue({
      taskId: 'task-id',
      status: 'SUCCEEDED',
      base64: 'aW1n',
      mediaType: 'image/png',
    })
    vi.mocked(coverApi.upload).mockResolvedValue(attachment)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.restoreAllMocks()
  })

  function mountEditor(cover = '/uploads/old-cover.png') {
    const formState = reactive({ cover, title: '尚未保存的标题', raw: '<p>尚未保存的正文</p>' })
    const ToolbarContainer = defineComponent({
      setup: () => () => h('div', [h(CoverToolbarItem, toolbarProps)]),
    })
    const HostEditor = defineComponent({
      props: ['cover', 'title', 'raw'],
      emits: ['update:cover'],
      setup: (props) => () =>
        h('div', [
          h('img', { 'data-testid': 'current-cover', src: props.cover }),
          h(ToolbarContainer),
        ]),
    })
    wrapper = mount(
      defineComponent({
        setup: () => () =>
          h(HostEditor, {
            ...formState,
            'onUpdate:cover': (url: string) => {
              formState.cover = url
            },
          }),
      }),
    )
    return formState
  }

  async function clickButton(text: string) {
    const button = wrapper!.findAll('button').find((button) => button.text() === text)
    expect(button, `找不到按钮：${text}`).toBeDefined()
    await button!.trigger('click')
    await flushPromises()
  }

  async function openUpload() {
    await clickButton('AI 生成封面')
    await clickButton('生成')
    await clickButton('上传')
  }

  it.each([undefined, [], ['core:posts:manage'], ['system:plugins:manage']])(
    '权限为 %j 时隐藏编辑器入口且不请求插件接口',
    async (permissions) => {
      permissionState.permissions = permissions
      mountEditor()
      await flushPromises()

      expect(wrapper!.findAll('button')).toHaveLength(0)
      expect(wrapper!.findAll('section')).toHaveLength(0)
      expect(coverApi.getConfig).not.toHaveBeenCalled()
      expect(coverApi.listTasks).not.toHaveBeenCalled()
      expect(coverApi.generate).not.toHaveBeenCalled()
    },
  )

  it.each(['', '/uploads/old-cover.png'])('封面原值为 %s 时上传完成后更新绑定', async (cover) => {
    const formState = mountEditor(cover)
    await openUpload()
    expect(formState.cover).toBe(cover)

    await clickButton('确认上传')

    expect(coverApi.upload).toHaveBeenCalledOnce()
    expect(formState).toEqual({
      cover: '/uploads/cover.png',
      title: '尚未保存的标题',
      raw: '<p>尚未保存的正文</p>',
    })
    expect(wrapper!.get('[data-testid="current-cover"]').attributes('src')).toBe(
      '/uploads/cover.png',
    )
    expect(Toast.success).toHaveBeenCalledOnce()
    expect(Toast.warning).not.toHaveBeenCalled()
    expect(wrapper!.findAll('section')).toHaveLength(0)
  })

  it('保留用户自定义的完整附件名及后缀', async () => {
    const formState = mountEditor()
    await openUpload()
    const inputs = wrapper!.findAll('input')
    await inputs[inputs.length - 1]!.setValue('自定义封面.jpeg')

    await clickButton('确认上传')

    const request = vi.mocked(coverApi.upload).mock.calls[0]![0]
    expect(request.displayName).toBe('自定义封面.jpeg')
    expect((request.file as File).name).toBe('自定义封面.jpeg')
    expect(request.taskId).toBe('task-id')
    expect(formState.cover).toBe('/uploads/cover.png')
  })

  it('上传失败时保留原封面和上传窗口，可再次上传', async () => {
    vi.mocked(coverApi.upload).mockRejectedValueOnce(new Error('upload failed'))
    const formState = mountEditor()
    await openUpload()

    await clickButton('确认上传')

    expect(formState.cover).toBe('/uploads/old-cover.png')
    expect(wrapper!.findAll('section')).toHaveLength(2)
    expect(Toast.success).not.toHaveBeenCalled()

    await clickButton('确认上传')

    expect(formState.cover).toBe('/uploads/cover.png')
    expect(coverApi.upload).toHaveBeenCalledTimes(2)
  })

  it('附件没有有效地址时保留原封面并提示手动选择', async () => {
    vi.mocked(coverApi.upload).mockResolvedValue({ ...attachment, status: undefined })
    const formState = mountEditor()
    await openUpload()

    await clickButton('确认上传')

    expect(formState.cover).toBe('/uploads/old-cover.png')
    expect(Toast.warning).toHaveBeenCalledOnce()
    expect(Toast.success).not.toHaveBeenCalled()
    expect(coverApi.upload).toHaveBeenCalledOnce()
  })

  it('宿主未提供封面更新事件时提示附件已保存，可手动选择', async () => {
    wrapper = mount(CoverToolbarItem, { props: toolbarProps })
    await openUpload()

    await clickButton('确认上传')

    expect(Toast.warning).toHaveBeenCalledOnce()
    expect(Toast.success).not.toHaveBeenCalled()
    expect(coverApi.upload).toHaveBeenCalledOnce()
    expect(wrapper.findAll('section')).toHaveLength(0)
  })
})
