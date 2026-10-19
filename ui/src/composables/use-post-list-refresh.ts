import { Toast } from '@halo-dev/components'
import { inject } from 'vue'

/** 通过 Halo 2.26 注入的查询缓存刷新文章列表，保留分页、筛选和服务端最新封面。 */
export function usePostListRefresh() {
  const client = inject<
    | {
        invalidateQueries(
          filters: { queryKey: string[] },
          options: { throwOnError: boolean },
        ): Promise<void>
      }
    | undefined
  >('VUE_QUERY_CLIENT', undefined)

  return async () => {
    try {
      if (!client) throw new Error('宿主未提供文章列表缓存')
      await client.invalidateQueries({ queryKey: ['posts'] }, { throwOnError: true })
      return true
    } catch {
      Toast.warning('封面任务已结束，但列表显示刷新失败，请刷新文章列表')
      return false
    }
  }
}
