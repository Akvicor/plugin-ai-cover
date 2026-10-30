import type { CoverTaskResponse } from '@/api'

const STAGE_LABELS: Record<string, string> = {
  QUEUED: '排队等待',
  DESCRIBING: '正在理解文章',
  GENERATING: '正在生成图片',
  COMPRESSING: '正在压缩图片',
  UPLOADING: '正在上传附件',
  APPLYING: '正在设置封面',
}

/** 将后台真实阶段和结果转换为列表徽标与编辑器窗口共用的状态文案。 */
export function coverTaskLabel(task: CoverTaskResponse): string {
  switch (task.status) {
    case 'RUNNING':
      return STAGE_LABELS[task.stage ?? ''] ?? '正在生成封面'
    case 'SUCCEEDED':
      return task.source === 'EDITOR' ? '图片已生成' : '封面已设置'
    case 'SKIPPED':
      return '已跳过'
    case 'FAILED':
      return '封面生成失败'
    case 'CANCELLED':
      return '已取消'
    default:
      return '状态未知'
  }
}

/** 进行中显示开始时间，结束后显示结束时间。 */
export function coverTaskTime(task: CoverTaskResponse): string {
  const iso = task.status === 'RUNNING' ? task.createdAt : task.finishedAt
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${task.status === 'RUNNING' ? '开始于' : '结束于'} ${date.toLocaleString()}`
}
