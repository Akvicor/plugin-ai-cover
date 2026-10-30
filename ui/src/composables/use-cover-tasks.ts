import { computed, onScopeDispose, ref } from 'vue'
import { coverApi, type BackgroundCoverRequestModeEnum, type CoverTaskResponse } from '@/api'

/** 存在进行中的任务时的刷新间隔，任务在服务端后台执行，需持续查询直到结束。 */
const POLL_INTERVAL = 5000

/** 单轮查询的最长持续时间，避免服务端异常时无限请求；新的提交重新计时。 */
const POLL_MAX_DURATION = 30 * 60 * 1000

/** 文章列表各行与编辑器窗口共用的任务状态，键为文章 metadata.name。 */
const tasks = ref<Record<string, CoverTaskResponse>>({})

/** 本页观察到由进行中转为结束的任务，用于判断跳过结果是否需要刷新文章列表。 */
const finishedInPage = new Set<string>()

let loaded = false
let pending: Promise<void> | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let deadline = 0
let observers = 0
// 本地标记生成中后递增，迟于标记返回的旧响应被丢弃并重新查询。
let revision = 0

function hasRunning() {
  return Object.values(tasks.value).some((task) => task.status === 'RUNNING')
}

function clearTimer() {
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
}

function schedule() {
  clearTimer()
  if (observers === 0 || !hasRunning()) {
    deadline = 0
    return
  }
  // 排队时长取决于前面的任务；队列耗尽后再计算运行阶段的查询上限。
  if (Object.values(tasks.value).some((task) => task.stage === 'QUEUED')) deadline = 0
  else if (deadline === 0) deadline = Date.now() + POLL_MAX_DURATION
  if (deadline !== 0 && Date.now() >= deadline) {
    deadline = 0
    return
  }
  timer = setTimeout(() => {
    timer = undefined
    void loadTasks(true)
  }, POLL_INTERVAL)
}

function replace(list: CoverTaskResponse[]) {
  const next: Record<string, CoverTaskResponse> = {}
  for (const task of list) {
    if (!task.postName) continue
    const previous = tasks.value[task.postName]
    if (
      previous?.taskId === task.taskId &&
      previous.status === 'RUNNING' &&
      task.status !== 'RUNNING'
    ) {
      finishedInPage.add(task.taskId)
    }
    next[task.postName] = task
  }
  tasks.value = next
}

/** 读取全部任务摘要；已加载且未强制刷新时复用缓存，并发调用合并为同一请求。 */
export function loadTasks(force = false): Promise<void> {
  if (loaded && !force) return Promise.resolve()
  if (pending) return pending
  const started = revision
  let stale = false
  pending = coverApi
    .listTasks()
    .then(({ tasks: list }) => {
      if (started !== revision) {
        stale = true
        return
      }
      replace(list)
      loaded = true
    })
    .catch(() => {
      // 错误由 Halo 的全局拦截器提示；仍有进行中的任务时继续按间隔重试。
    })
    .finally(() => {
      pending = undefined
      if (stale) void loadTasks(true)
      else schedule()
    })
  return pending
}

/** 提交成功后立即在本地记录任务，徽标即时变为生成中并开始查询结果。 */
export function upsertTask(task: CoverTaskResponse) {
  if (!task.postName) return
  revision++
  tasks.value = { ...tasks.value, [task.postName]: task }
  deadline = 0
  void loadTasks(true)
}

/** 文章列表提交后台任务；请求错误（如正在生成、已有封面）由 Halo 的全局拦截器提示。 */
export async function submitBackgroundCover(
  postName: string,
  mode: BackgroundCoverRequestModeEnum,
) {
  try {
    upsertTask(await coverApi.submitBackground(postName, mode))
  } catch {
    // 提示已由全局拦截器展示，菜单操作无需额外处理。
  }
}

/**
 * 观察共享任务状态。首个观察者出现时查询一次，之后各组件复用缓存；
 * 只在存在进行中的任务时定时查询，最后一个观察者离开时停止。
 */
export function useCoverTasks() {
  observers++
  void loadTasks(observers === 1)
  onScopeDispose(() => {
    observers--
    if (observers === 0) {
      clearTimer()
      deadline = 0
    }
  })
  return {
    tasks: computed(() => tasks.value),
    finishedInPage: (taskId: string) => finishedInPage.has(taskId),
    refresh: () => loadTasks(true),
  }
}
