import {
  axiosInstance,
  coreApiClient,
  PostV1alpha1ApiAxiosParamCreator,
  type Post,
} from '@halo-dev/api-client'
import { onMounted, onScopeDispose, watch, type Ref } from 'vue'

/** Axios 的公共拦截边界同时接收对象和生成客户端已序列化的 JSON。 */
function postBody(data: unknown): Post | undefined {
  let value = data
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return
    }
  }
  if (!value || typeof value !== 'object') return
  const post = value as Post
  if (
    post.apiVersion === 'content.halo.run/v1alpha1' &&
    post.kind === 'Post' &&
    typeof post.metadata?.name === 'string' &&
    post.spec &&
    typeof post.spec === 'object'
  ) {
    return post
  }
}

/**
 * 编辑器保存时合并未被用户编辑的封面，避免旧页面覆盖后台已写入的新封面。
 * 只作用于当前文章的 Post 更新请求，组件销毁时移除拦截器。
 */
export function useCoverSaveGuard(
  postName: Readonly<Ref<string | undefined>>,
  readCover: () => string,
  writeCover: (value: string) => void,
  enabled: () => boolean,
) {
  let baseline = readCover()
  let requestInterceptor: number | undefined
  let responseInterceptor: number | undefined
  let disposed = false
  const postRequests = PostV1alpha1ApiAxiosParamCreator()

  watch(postName, () => {
    baseline = readCover()
  })

  /** 后台完成事件读取服务端当前值，既兼容旧任务摘要，也保留用户尚未保存的封面编辑。 */
  async function refresh() {
    const name = postName.value
    const previous = baseline
    if (!name || !enabled()) return
    if (readCover() !== previous) return false
    const { data } = await coreApiClient.content.post.getPost({ name })
    if (disposed || name !== postName.value) return
    if (readCover() !== previous) return false
    baseline = data.spec.cover ?? ''
    writeCover(baseline)
    return true
  }

  onMounted(() => {
    baseline = readCover()
    requestInterceptor = axiosInstance.interceptors.request.use(async (request) => {
      if (!enabled() || request.method?.toLowerCase() !== 'put') return request
      const name = postName.value
      if (!name || request.url !== (await postRequests.getPost(name)).url) return request
      const post = postBody(request.data)
      if (post?.metadata.name !== name) return request
      const previous = baseline
      if ((post.spec.cover ?? '') !== previous) return request

      const { data: latest } = await coreApiClient.content.post.getPost({ name })
      const cover = latest.spec.cover ?? ''
      const merged: Post = { ...post, spec: { ...post.spec, cover } }
      request.data = typeof request.data === 'string' ? JSON.stringify(merged) : merged
      if (!disposed && name === postName.value) {
        baseline = cover
        if (readCover() === previous) writeCover(cover)
      }
      return request
    })
    responseInterceptor = axiosInstance.interceptors.response.use(async (response) => {
      const name = postName.value
      if (
        name &&
        response.config.method?.toLowerCase() === 'put' &&
        response.config.url === (await postRequests.getPost(name)).url
      ) {
        const post = postBody(response.data)
        if (post && post.metadata.name === postName.value) baseline = post.spec.cover ?? ''
      }
      return response
    })
  })

  onScopeDispose(() => {
    disposed = true
    if (requestInterceptor !== undefined)
      axiosInstance.interceptors.request.eject(requestInterceptor)
    if (responseInterceptor !== undefined)
      axiosInstance.interceptors.response.eject(responseInterceptor)
  })

  return { refresh }
}
