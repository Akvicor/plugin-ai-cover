import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import type { coverApi as CoverApi, CoverTaskListResponse, CoverTaskResponse } from '@/api'

// 每个用例都会重新加载模块，接口桩在提升阶段创建一次，使重新执行的模拟工厂返回同一组函数。
const coverApi = vi.hoisted(() => ({
  listTasks: vi.fn<typeof CoverApi.listTasks>(),
  submitBackground: vi.fn<typeof CoverApi.submitBackground>(),
}))
vi.mock('@/api', () => ({ coverApi }))

type Store = typeof import('../use-cover-tasks')

const running: CoverTaskResponse = {
  taskId: 'task',
  postName: 'post',
  status: 'RUNNING',
  source: 'LIST',
}

describe('共享封面任务状态', () => {
  let store: Store
  const scopes: ReturnType<typeof effectScope>[] = []

  beforeEach(async () => {
    vi.resetAllMocks()
    vi.useFakeTimers()
    // 状态缓存是模块级单例，每个用例重新加载模块，避免用例之间共享状态。
    vi.resetModules()
    store = await import('../use-cover-tasks')
  })

  afterEach(() => {
    scopes.forEach((scope) => scope.stop())
    scopes.length = 0
    vi.useRealTimers()
  })

  function observe() {
    const scope = effectScope()
    scopes.push(scope)
    return { scope, state: scope.run(() => store.useCoverTasks())! }
  }

  it('多个观察者共用一次查询，没有进行中的任务时不再请求', async () => {
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [] })
    const first = observe()
    observe()
    observe()
    await flushPromises()

    expect(coverApi.listTasks).toHaveBeenCalledOnce()
    expect(first.state.tasks.value).toEqual({})
    await vi.advanceTimersByTimeAsync(60_000)
    expect(coverApi.listTasks).toHaveBeenCalledOnce()
  })

  it('存在进行中的任务时按间隔查询，任务结束后停止', async () => {
    vi.mocked(coverApi.listTasks)
      .mockResolvedValueOnce({ tasks: [running] })
      .mockResolvedValueOnce({ tasks: [running] })
      .mockResolvedValue({ tasks: [{ ...running, status: 'SUCCEEDED', stage: 'COMPLETED' }] })
    const { state } = observe()
    await flushPromises()
    expect(state.tasks.value.post?.status).toBe('RUNNING')

    await vi.advanceTimersByTimeAsync(5000)
    await vi.advanceTimersByTimeAsync(5000)
    expect(coverApi.listTasks).toHaveBeenCalledTimes(3)
    expect(state.tasks.value.post?.status).toBe('SUCCEEDED')
    expect(state.finishedInPage('task')).toBe(true)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(coverApi.listTasks).toHaveBeenCalledTimes(3)
  })

  it('最后一个观察者离开后停止查询，再次打开时重新查询一次', async () => {
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [running] })
    const first = observe()
    await flushPromises()
    first.scope.stop()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(coverApi.listTasks).toHaveBeenCalledOnce()

    observe()
    await flushPromises()
    expect(coverApi.listTasks).toHaveBeenCalledTimes(2)
  })

  it('提交后立即标记生成中，迟到的旧查询结果不会覆盖本地状态', async () => {
    let resolveOld!: (value: CoverTaskListResponse) => void
    vi.mocked(coverApi.listTasks)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve
          }),
      )
      .mockResolvedValue({ tasks: [running] })
    const { state } = observe()

    store.upsertTask(running)
    expect(state.tasks.value.post?.status).toBe('RUNNING')
    resolveOld({ tasks: [] })
    await flushPromises()

    expect(state.tasks.value.post?.taskId).toBe('task')
    expect(coverApi.listTasks).toHaveBeenCalledTimes(2)
  })

  it('列表提交成功后记录任务，失败时不改变状态也不抛出', async () => {
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [] })
    const { state } = observe()
    await flushPromises()
    vi.mocked(coverApi.submitBackground)
      .mockResolvedValueOnce(running)
      .mockRejectedValueOnce({ response: { status: 409 } })
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [running] })

    await store.submitBackgroundCover('post', 'REPLACE')
    expect(coverApi.submitBackground).toHaveBeenCalledWith('post', 'REPLACE')
    expect(state.tasks.value.post?.status).toBe('RUNNING')

    await expect(store.submitBackgroundCover('other', 'FILL')).resolves.toBeUndefined()
    expect(state.tasks.value.other).toBeUndefined()
  })

  it('查询失败时若仍有进行中的任务则继续重试', async () => {
    vi.mocked(coverApi.listTasks)
      .mockResolvedValueOnce({ tasks: [running] })
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue({ tasks: [{ ...running, status: 'FAILED', message: '模型不可用' }] })
    const { state } = observe()
    await flushPromises()

    await vi.advanceTimersByTimeAsync(5000)
    await vi.advanceTimersByTimeAsync(5000)
    expect(state.tasks.value.post?.status).toBe('FAILED')
    expect(coverApi.listTasks).toHaveBeenCalledTimes(3)
  })

  it('单轮查询超过最长持续时间后停止', async () => {
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [running] })
    observe()
    await flushPromises()

    await vi.advanceTimersByTimeAsync(31 * 60 * 1000)
    const calls = vi.mocked(coverApi.listTasks).mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(vi.mocked(coverApi.listTasks).mock.calls.length).toBe(calls)
  })

  it('有排队任务时持续查询，进入执行后恢复查询时限', async () => {
    vi.mocked(coverApi.listTasks).mockResolvedValue({ tasks: [{ ...running, stage: 'QUEUED' }] })
    observe()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(31 * 60_000)
    const queuedCalls = vi.mocked(coverApi.listTasks).mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(coverApi.listTasks).toHaveBeenCalledTimes(queuedCalls + 1)
    vi.mocked(coverApi.listTasks).mockResolvedValue({
      tasks: [{ ...running, stage: 'GENERATING' }],
    })
    await vi.advanceTimersByTimeAsync(32 * 60_000)
    const completedWindow = vi.mocked(coverApi.listTasks).mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(coverApi.listTasks).toHaveBeenCalledTimes(completedWindow)
  })
})
