<script lang="ts">
// 列表行组件会随文章数据变化重建，已刷新过的任务在模块内记录，保证每个任务最多刷新一次列表。
const refreshedTasks = new Set<string>()

function escapeHtml(text: string) {
  return text.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`)
}
</script>

<script lang="ts" setup>
import { IconImageAddLine, VEntityField, vTooltip } from '@halo-dev/components'
import { computed, watch } from 'vue'
import { useCoverTasks } from '@/composables/use-cover-tasks'
import { usePostListRefresh } from '@/composables/use-post-list-refresh'
import { coverTaskLabel, coverTaskTime } from '@/utils/cover-task'

const props = defineProps<{ postName: string; cover?: string }>()
const { tasks, finishedInPage } = useCoverTasks()
const refreshPosts = usePostListRefresh()

/** 文章行只展示列表和发布触发的后台任务，编辑器任务由编辑器窗口展示。 */
const task = computed(() => {
  const value = tasks.value[props.postName]
  return value && value.source !== 'EDITOR' ? value : undefined
})

const label = computed(() => (task.value ? coverTaskLabel(task.value) : ''))

// 复用 Halo 的 v-tooltip：状态、原因和时间逐行展示，动态内容转义后以 HTML 承载。
const tooltip = computed(() => {
  if (!task.value) return { content: '', html: true, theme: 'tooltip' }
  const reason = task.value.status === 'RUNNING' ? '' : task.value.message
  const rows = [label.value, reason, coverTaskTime(task.value)].filter(Boolean) as string[]
  return { content: rows.map(escapeHtml).join('<br />'), html: true, theme: 'tooltip' }
})

// 成功后列表封面与新附件不一致时刷新一次；跳过只在本页看到任务结束时刷新，以展示期间设置的封面。
watch(
  [task, () => props.cover],
  async ([value, cover]) => {
    if (!value || refreshedTasks.has(value.taskId)) return
    const permalink = value.attachment?.status?.permalink
    const outdated =
      value.status === 'SUCCEEDED'
        ? !!permalink && cover !== permalink
        : value.status === 'SKIPPED' && finishedInPage(value.taskId)
    if (!outdated) return
    refreshedTasks.add(value.taskId)
    if (!(await refreshPosts())) refreshedTasks.delete(value.taskId)
  },
  { immediate: true },
)
</script>

<template>
  <VEntityField v-if="task">
    <template #description>
      <span
        v-tooltip="tooltip"
        role="status"
        :aria-label="label"
        :data-status="task.status"
        class="cover-task-status"
      >
        <IconImageAddLine class="cover-task-status__icon" />
      </span>
    </template>
  </VEntityField>
</template>

<style scoped>
/* 颜色区分状态：蓝色=生成中、绿色=已设置、红色=失败、灰色=跳过或取消 */
.cover-task-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #2563eb;
  cursor: default;
}

.cover-task-status[data-status='RUNNING'] {
  animation: cover-task-pulse 1.5s ease-in-out infinite;
}

.cover-task-status[data-status='SUCCEEDED'] {
  color: #16a34a;
}

.cover-task-status[data-status='FAILED'] {
  color: #dc2626;
}

.cover-task-status[data-status='SKIPPED'],
.cover-task-status[data-status='CANCELLED'] {
  color: #9ca3af;
}

.cover-task-status__icon {
  display: block;
  font-size: 18px;
}

@keyframes cover-task-pulse {
  50% {
    opacity: 0.4;
  }
}
</style>
