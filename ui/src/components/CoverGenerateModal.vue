<script lang="ts" setup>
import { Toast, VButton, VLoading, VModal } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { Attachment, CoverConfigResponse } from '@/api'
import { coverApi } from '@/api'
import { buildDefaultFileName, toDataUrl } from '@/utils/cover'
import CoverUploadModal from '@/components/CoverUploadModal.vue'

interface GeneratedImage {
  base64: string
  mediaType: string
}

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'uploaded', attachment: Attachment): void
}>()

const route = useRoute()

/** 轮询生成任务状态的间隔。 */
const POLL_INTERVAL = 2000

/** 文章 metadata.name，后端据此读取标题与正文。 */
const postName = ref<string>()
/** 文章标题由后端读取，编辑值用于本次生成和附件默认名称。 */
const title = ref('')
/** 提示词，默认取插件设置，用户可修改。 */
const prompt = ref('')
const config = ref<CoverConfigResponse>({})
const generating = ref(false)
const generated = ref<GeneratedImage>()
const errorMessage = ref('')
const previewVisible = ref(false)
const uploadVisible = ref(false)

const previewSrc = computed(() =>
  generated.value ? toDataUrl(generated.value.base64, generated.value.mediaType) : undefined,
)

let pollTimer: number | undefined
let stopped = false

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

onBeforeUnmount(() => {
  stopped = true
  stopPolling()
})

onMounted(async () => {
  postName.value = typeof route.query.name === 'string' ? route.query.name : undefined
  try {
    // 标题与提示词均由后端提供：标题来自文章，提示词来自插件设置。
    config.value = await coverApi.getConfig(postName.value)
    title.value = config.value.title ?? ''
    prompt.value = config.value.prompt ?? ''
  } catch (error) {
    // 接口错误由 Halo 的 axios 全局拦截器统一提示
    console.error('读取封面生成配置失败', error)
  }
})

async function handleGenerate() {
  if (!postName.value?.trim()) {
    errorMessage.value = '无法识别当前文章，请先保存文章并重新打开生成窗口'
    return
  }
  if (!prompt.value.trim()) {
    Toast.warning('请先填写提示词')
    return
  }
  stopPolling()
  generating.value = true
  errorMessage.value = ''
  generated.value = undefined
  try {
    // 正文与标题由后端根据 postName 读取；title 仅作为可选的标题覆盖值。
    const task = await coverApi.generate({
      postName: postName.value,
      title: title.value,
      prompt: prompt.value,
    })
    pollGenerateTask(task.taskId)
  } catch (error) {
    generating.value = false
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
  }
}

async function pollGenerateTask(taskId: string) {
  if (stopped) {
    return
  }
  try {
    const task = await coverApi.getGenerateTask(taskId)
    if (stopped) {
      return
    }
    if (task.status === 'SUCCEEDED' && task.base64) {
      generated.value = {
        base64: task.base64,
        mediaType: task.mediaType || 'image/png',
      }
      generating.value = false
      return
    }
    if (task.status === 'FAILED') {
      errorMessage.value = task.message || '封面图生成失败'
      generating.value = false
      return
    }
  } catch (error) {
    generating.value = false
    errorMessage.value = '生成任务查询失败，任务可能已过期，请重新生成'
    console.error('查询封面图生成任务失败', error)
    return
  }
  pollTimer = window.setTimeout(() => pollGenerateTask(taskId), POLL_INTERVAL)
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
          v-if="errorMessage"
          class="rounded-base border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {{ errorMessage }}
        </div>
        <div class="flex gap-2">
          <VButton type="secondary" :loading="generating" @click="handleGenerate">
            {{ generating ? '生成中' : '生成' }}
          </VButton>
          <VButton :disabled="!generated || generating" @click="uploadVisible = true">上传</VButton>
        </div>
      </div>
      <div class="cover-preview rounded-lg border border-dashed border-gray-200 bg-gray-50 p-2">
        <div v-if="generating" class="flex flex-col items-center gap-2">
          <VLoading />
          <span class="text-sm text-gray-500">正在生成，请稍候</span>
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
