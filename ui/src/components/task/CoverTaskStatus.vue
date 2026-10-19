<script lang="ts" setup>
import type { CoverTaskResponse } from '@/api'
import { coverTaskLabel } from '@/utils/cover-task'

defineProps<{ task: CoverTaskResponse }>()
</script>

<template>
  <span
    role="status"
    aria-live="polite"
    class="inline-flex items-center gap-1.5 text-xs"
    :class="task.status === 'FAILED' ? 'text-red-600' : 'text-gray-600'"
  >
    <span
      class="inline-block h-2 w-2 rounded-full"
      :class="{
        'animate-pulse bg-blue-500': task.status === 'RUNNING',
        'bg-green-500': task.status === 'SUCCEEDED',
        'bg-red-500': task.status === 'FAILED',
        'bg-gray-400': task.status === 'SKIPPED' || task.status === 'CANCELLED',
      }"
    />
    {{ coverTaskLabel(task) }}
  </span>
</template>
