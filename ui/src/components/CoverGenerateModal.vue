<script lang="ts" setup>
import { Toast, VButton, VLoading, VModal } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Attachment, CoverConfigResponse, CoverTaskResponse } from '@/api'
import { coverApi } from '@/api'
import { buildDefaultFileName, toDataUrl } from '@/utils/cover'
import CoverUploadModal from '@/components/CoverUploadModal.vue'
import CoverTaskStatus from '@/components/task/CoverTaskStatus.vue'
import { upsertTask, useCoverTasks } from '@/composables/use-cover-tasks'
import { coverTaskLabel } from '@/utils/cover-task'

interface GeneratedImage {
  taskId: string
  base64: string
  mediaType: string
}

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'uploaded', attachment: Attachment): void
  (event: 'applied', attachment: Attachment): void
}>()

/** 文章 metadata.name，后端据此读取标题与正文。 */
const props = defineProps<{ postName?: string }>()
/** 文章标题由后端读取，编辑值用于本次生成和附件默认名称。 */
const title = ref('')
const titleEdited = ref(false)
/** 提示词，默认取插件设置，用户可修改。 */
const prompt = ref('')
const config = ref<CoverConfigResponse>({})
const submitting = ref(false)
const generated = ref<GeneratedImage>()
const errorMessage = ref('')
const imageErrorMessage = ref('')
const previewVisible = ref(false)
const uploadVisible = ref(false)

// 与文章列表共用任务状态：打开时查询一次，只在任务进行中定时查询。
const { tasks, refresh } = useCoverTasks()
const task = computed(() => (props.postName ? tasks.value[props.postName] : undefined))
/** 任何来源的进行中任务都会锁定文章，生成按钮不可用。 */
const running = computed(() => task.value?.status === 'RUNNING')
const generating = computed(() => submitting.value || running.value)
/** 列表或发布任务已在服务端设置封面，窗口展示该封面并同步编辑器。 */
const savedCover = computed(() =>
  task.value?.source !== 'EDITOR' && task.value?.status === 'SUCCEEDED'
    ? task.value.attachment?.status?.permalink
    : undefined,
)

const previewSrc = computed(
  () =>
    savedCover.value ??
    (generated.value ? toDataUrl(generated.value.base64, generated.value.mediaType) : undefined),
)

let stopped = false
let loadedTaskId: string | undefined
let loadingTaskId: string | undefined
let appliedTaskId: string | undefined

onBeforeUnmount(() => {
  stopped = true
})

watch(
  task,
  async (value, previous) => {
    if (!value) return
    if (value.taskId !== previous?.taskId) {
      errorMessage.value = ''
      imageErrorMessage.value = ''
      generated.value = undefined
    }
    if (!titleEdited.value && typeof value.title === 'string') title.value = value.title
    if (value.status === 'RUNNING') {
      errorMessage.value = ''
      return
    }
    if (value.status === 'FAILED') {
      errorMessage.value = value.message || '封面图生成失败'
      return
    }
    if (value.status !== 'SUCCEEDED') return
    if (value.source !== 'EDITOR') {
      if (value.attachment && appliedTaskId !== value.taskId) {
        appliedTaskId = value.taskId
        emit('applied', value.attachment)
      }
      return
    }
    await loadImage(value)
  },
  { immediate: true },
)

/** 读取编辑器任务的图片；成功后缓存，在途请求复用，失败后释放以便刷新时重试。 */
async function loadImage(value: CoverTaskResponse) {
  // 图片已上传或已过期时服务端不再保留，窗口不展示预览。
  if (value.source !== 'EDITOR' || value.status !== 'SUCCEEDED' || !value.imageAvailable) return
  if (loadedTaskId === value.taskId || loadingTaskId === value.taskId) return
  loadingTaskId = value.taskId
  try {
    const result = await coverApi.getGenerateTask(value.taskId)
    if (stopped || task.value?.taskId !== value.taskId) return
    if (result.base64) {
      generated.value = {
        taskId: value.taskId,
        base64: result.base64,
        mediaType: result.mediaType || 'image/png',
      }
      loadedTaskId = value.taskId
      imageErrorMessage.value = ''
    } else {
      imageErrorMessage.value = '图片已过期，请重新生成'
    }
  } catch {
    if (!stopped && task.value?.taskId === value.taskId) {
      imageErrorMessage.value = '读取图片失败，请刷新进度重试'
    }
  } finally {
    if (loadingTaskId === value.taskId) loadingTaskId = undefined
  }
}

/** 刷新任务状态后重新读取图片，不依赖状态对象是否变化。 */
async function handleRefresh() {
  await refresh()
  if (task.value) await loadImage(task.value)
}

onMounted(async () => {
  try {
    // 标题与提示词均由后端提供：标题来自文章，提示词来自插件设置。
    config.value = await coverApi.getConfig(props.postName)
    if (!titleEdited.value) title.value = task.value?.title ?? config.value.title ?? ''
    prompt.value = config.value.prompt ?? ''
  } catch (error) {
    // 接口错误由 Halo 的 axios 全局拦截器统一提示
    console.error('读取封面生成配置失败', error)
  }
})

async function handleGenerate() {
  if (generating.value) return
  if (!props.postName?.trim()) {
    errorMessage.value = '无法识别当前文章，请先保存文章并重新打开生成窗口'
    return
  }
  if (!prompt.value.trim()) {
    Toast.warning('请先填写提示词')
    return
  }
  submitting.value = true
  errorMessage.value = ''
  imageErrorMessage.value = ''
  generated.value = undefined
  try {
    // 正文与标题由后端根据 postName 读取；title 仅作为可选的标题覆盖值。
    const submittedTask = await coverApi.generate({
      postName: props.postName,
      title: title.value,
      prompt: prompt.value,
    })
    // 立即在共享状态中标记生成中，并查询真实阶段与结果。
    upsertTask(submittedTask)
  } catch (error) {
    if (stopped) return
    const body = isAxiosError<unknown>(error) ? error.response?.data : undefined
    errorMessage.value =
      typeof body === 'object' &&
      body !== null &&
      'detail' in body &&
      typeof body.detail === 'string' &&
      body.detail.trim()
        ? body.detail
        : '提交生成请求失败，请稍后重试'
    console.error('提交封面图生成任务失败', error)
  } finally {
    submitting.value = false
  }
}

function handleUploaded(attachment: Attachment) {
  uploadVisible.value = false
  emit('uploaded', attachment)
  emit('close')
}
</script>

<template>
  <VModal :title="'AI 生成封面'" :width="920" mount-to-body @close="emit('close')">
    <div class="cover-layout">
      <div class="cover-form flex flex-col gap-4">
        <div class="flex flex-col gap-1.5">
          <label class="text-sm font-medium text-gray-700">文章标题</label>
          <!--
            边框做在外层容器上：Halo 控制台全局重置了 input/textarea 的边框
            （见 ui/src/styles/tailwind.css 中的 border-none），因此输入框自身不画边框，
            与 Halo FormKit 主题（src/formkit/theme.ts）的写法保持一致。
          -->
          <div
            class="relative box-border inline-flex h-9 w-full items-center overflow-hidden rounded-base border border-gray-300 bg-white transition-all focus-within:border-primary focus-within:shadow-sm"
          >
            <input
              v-model="title"
              @input="titleEdited = true"
              type="text"
              class="block w-full px-3 text-sm text-black transition-all"
              placeholder="用于生成附件名称"
            />
          </div>
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-sm font-medium text-gray-700">提示词</label>
          <div
            class="relative box-border flex w-full items-stretch overflow-hidden rounded-base border border-gray-300 bg-white transition-all focus-within:border-primary focus-within:shadow-sm"
          >
            <textarea
              v-model="prompt"
              rows="7"
              class="block w-full resize-none px-3 py-2 text-sm text-black transition-all"
              placeholder="请输入生成封面图的提示词"
            />
          </div>
        </div>
        <div
          v-if="errorMessage || imageErrorMessage"
          class="rounded-base border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {{ errorMessage || imageErrorMessage }}
        </div>
        <div v-if="task" class="flex flex-col gap-1.5">
          <CoverTaskStatus :task="task" />
          <p v-if="running && !submitting" class="text-sm text-gray-600">
            {{ task.stage === 'QUEUED' ? '该文章正在排队等待' : '该文章正在生成封面' }}
          </p>
          <p
            v-if="task.status === 'SKIPPED' || task.status === 'CANCELLED'"
            class="text-sm text-gray-600"
          >
            {{ task.message }}
          </p>
        </div>
        <div class="flex gap-2">
          <VButton type="secondary" :loading="generating" @click="handleGenerate">
            {{ generating ? '生成中' : '生成' }}
          </VButton>
          <VButton
            :disabled="!generated || generating || !!savedCover"
            @click="uploadVisible = true"
          >
            {{ savedCover ? '封面已设置' : '上传' }}
          </VButton>
          <VButton v-if="imageErrorMessage" @click="handleRefresh">刷新进度</VButton>
        </div>
      </div>
      <div class="cover-preview rounded-lg border border-dashed border-gray-200 bg-gray-50 p-2">
        <div v-if="generating" class="flex flex-col items-center gap-2">
          <VLoading />
          <span class="text-sm text-gray-500">{{
            task ? coverTaskLabel(task) : '正在提交任务'
          }}</span>
        </div>
        <img
          v-else-if="previewSrc"
          :src="previewSrc"
          class="cover-preview-image rounded-md"
          alt="生成的封面图"
          @click="previewVisible = true"
        />
        <span v-else class="text-sm text-gray-400">生成后在此预览</span>
      </div>
    </div>
  </VModal>

  <VModal
    v-if="previewVisible"
    :title="'预览'"
    :width="960"
    mount-to-body
    @close="previewVisible = false"
  >
    <img v-if="previewSrc" :src="previewSrc" class="w-full object-contain" alt="封面图预览" />
  </VModal>

  <CoverUploadModal
    v-if="uploadVisible && generated"
    :base64="generated.base64"
    :media-type="generated.mediaType"
    :default-file-name="buildDefaultFileName(title, generated?.mediaType)"
    :config="config"
    :task-id="generated.taskId"
    @close="uploadVisible = false"
    @uploaded="handleUploaded"
  />
</template>

<style scoped>
.cover-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1.5rem;
}

.cover-form {
  min-width: 0;
}

.cover-preview {
  display: flex;
  min-width: 0;
  min-height: 280px;
  align-items: center;
  justify-content: center;
}

.cover-preview-image {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  max-height: 360px;
  object-fit: contain;
  cursor: zoom-in;
}

@media (min-width: 768px) {
  .cover-layout {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
