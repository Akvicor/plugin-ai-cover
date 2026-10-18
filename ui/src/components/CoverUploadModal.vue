<script lang="ts" setup>
import { coreApiClient, type Group, type Policy } from '@halo-dev/api-client'
import { Toast, VButton, VLoading, VModal } from '@halo-dev/components'
import { onMounted, ref, watch } from 'vue'
import type { Attachment, CoverConfigResponse } from '@/api'
import { coverApi } from '@/api'
import { toImageFile } from '@/utils/cover'

const props = defineProps<{
  base64: string
  mediaType?: string
  defaultFileName: string
  config: CoverConfigResponse
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'uploaded', attachment: Attachment): void
}>()

const displayName = ref(props.defaultFileName)
const policyName = ref(props.config.policyName ?? '')
const groupName = ref(props.config.groupName ?? '')
const policies = ref<Policy[]>([])
const groups = ref<Group[]>([])
const loading = ref(true)
const uploading = ref(false)

// 默认名称会随文章标题或图片类型变化；在用户尚未手动修改前保持同步。
const displayNameEdited = ref(false)

watch(
  () => props.defaultFileName,
  (value) => {
    if (!displayNameEdited.value) {
      displayName.value = value
    }
  },
)

onMounted(async () => {
  try {
    const [policyResult, groupResult] = await Promise.all([
      coreApiClient.storage.policy.listPolicy({ page: 1, size: 1000 }),
      coreApiClient.storage.group.listGroup({
        page: 1,
        size: 1000,
        labelSelector: ['!halo.run/hidden'],
      }),
    ])
    policies.value = policyResult.data.items ?? []
    groups.value = groupResult.data.items ?? []
    if (!policyName.value) {
      policyName.value = policies.value[0]?.metadata.name ?? ''
    }
  } catch (error) {
    // 接口错误由 Halo 的 axios 全局拦截器统一提示
    console.error('读取存储策略或分组失败', error)
  } finally {
    loading.value = false
  }
})

async function handleConfirm() {
  if (!displayName.value.trim()) {
    Toast.warning('请填写附件名称')
    return
  }
  if (!policyName.value) {
    Toast.warning('请选择存储策略')
    return
  }
  uploading.value = true
  try {
    const attachment = await coverApi.upload({
      file: toImageFile(props.base64, displayName.value.trim(), props.mediaType),
      displayName: displayName.value.trim(),
      policyName: policyName.value,
      groupName: groupName.value || undefined,
    })
    emit('uploaded', attachment)
  } catch (error) {
    console.error('上传封面图失败', error)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <VModal :title="'上传封面图'" :width="520" mount-to-body @close="emit('close')">
    <!-- 固定正文根节点，让加载状态切换在滚动容器内部完成。 -->
    <div>
      <div v-if="loading" class="flex justify-center py-6">
        <VLoading />
      </div>
      <div v-else class="flex flex-col gap-4">
        <div class="flex flex-col gap-1.5">
          <label class="text-sm font-medium text-gray-700">附件标题</label>
          <!-- 边框做在外层容器上，与 Halo FormKit 主题保持一致（input 自身的边框被全局重置） -->
          <div
            class="relative box-border inline-flex h-9 w-full items-center overflow-hidden rounded-base border border-gray-300 bg-white transition-all focus-within:border-primary focus-within:shadow-sm"
          >
            <input
              v-model="displayName"
              type="text"
              class="block w-full px-3 text-sm text-black transition-all"
              placeholder="例如 文章标题-00.png"
              @input="displayNameEdited = true"
            />
          </div>
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-sm font-medium text-gray-700">存储策略</label>
          <div
            class="relative box-border inline-flex h-9 w-full items-center overflow-hidden rounded-base border border-gray-300 bg-white transition-all focus-within:border-primary focus-within:shadow-sm"
          >
            <select
              v-model="policyName"
              class="block w-full appearance-none bg-transparent px-3 text-sm text-black transition-all"
            >
              <option
                v-for="policy in policies"
                :key="policy.metadata.name"
                :value="policy.metadata.name"
              >
                {{ policy.spec?.displayName || policy.metadata.name }}
              </option>
            </select>
          </div>
        </div>
        <div class="flex flex-col gap-1.5">
          <label class="text-sm font-medium text-gray-700">分组</label>
          <div
            class="relative box-border inline-flex h-9 w-full items-center overflow-hidden rounded-base border border-gray-300 bg-white transition-all focus-within:border-primary focus-within:shadow-sm"
          >
            <select
              v-model="groupName"
              class="block w-full appearance-none bg-transparent px-3 text-sm text-black transition-all"
            >
              <option value="">不分组</option>
              <option
                v-for="group in groups"
                :key="group.metadata.name"
                :value="group.metadata.name"
              >
                {{ group.spec?.displayName || group.metadata.name }}
              </option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <VButton @click="emit('close')">取消</VButton>
        <VButton type="secondary" :loading="uploading" @click="handleConfirm">确认上传</VButton>
      </div>
    </template>
  </VModal>
</template>
