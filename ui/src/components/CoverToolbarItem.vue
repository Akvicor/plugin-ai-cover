<script lang="ts" setup>
import { Toast } from '@halo-dev/components'
import { ToolbarItem, type ToolbarItemComponentProps } from '@halo-dev/richtext-editor'
import { utils } from '@halo-dev/ui-shared'
import { computed, getCurrentInstance, ref, type ComponentPublicInstance } from 'vue'
import { useRoute } from 'vue-router'
import type { Attachment } from '@/api'
import CoverGenerateModal from '@/components/CoverGenerateModal.vue'
import { useCoverSaveGuard } from '@/composables/editor/use-cover-save-guard'

const props = defineProps<ToolbarItemComponentProps>()
const instance = getCurrentInstance()
const route = useRoute()
const postName = computed(() =>
  typeof route.query.name === 'string' ? route.query.name : undefined,
)

const open = ref(false)

const coverGuard = useCoverSaveGuard(
  postName,
  () => {
    const cover = findCoverHost()?.$props.cover
    return typeof cover === 'string' ? cover : ''
  },
  (cover) => findCoverHost()?.$emit('update:cover', cover),
  () => !!findCoverHost() && !!utils.permission.getUserPermissions()?.includes('*'),
)

function handleOpen() {
  // 权限未初始化或用户不是超级管理员时，保持关闭且不发起接口请求。
  if (!utils.permission.getUserPermissions()?.includes('*')) return
  open.value = true
}

async function handleCover(attachment: Attachment, saved = false) {
  const host = findCoverHost()
  if (!host) {
    Toast.warning('封面图已保存，当前编辑器未能同步，请在保留编辑内容后重新打开文章')
    return
  }
  if (saved) {
    try {
      if ((await coverGuard.refresh()) === false) {
        Toast.warning('后台封面已更新，当前编辑器保留了你修改的封面')
      }
    } catch {
      // 请求失败由 Halo 的全局拦截器提示，保存前仍会重新检查服务端封面。
    }
    return
  }
  const url = utils.attachment.getUrl(attachment)
  if (!url?.trim()) {
    Toast.warning('封面图已上传到附件库，但附件地址暂不可用，请从附件库手动选择封面')
    return
  }
  host.$emit('update:cover', url)
  Toast.success('封面图已上传并设置为当前文章封面，请保存文章')
}

function findCoverHost(): ComponentPublicInstance<{ cover?: string }> | undefined {
  // Halo 2.26 的封面绑定由宿主编辑器的 update:cover 事件更新，随文章原有保存流程持久化。
  // 通过声明的属性与事件定位宿主，兼容中间工具栏组件层级，并保留编辑器内尚未保存的内容。
  let parent = instance?.proxy?.$parent
  while (parent) {
    const emits = parent.$options.emits
    const supportsCover = Array.isArray(emits)
      ? emits.includes('update:cover')
      : emits && 'update:cover' in emits
    if ('cover' in parent.$props && supportsCover) {
      return parent as ComponentPublicInstance<{ cover?: string }>
    }
    parent = parent.$parent
  }
}
</script>

<template>
  <ToolbarItem
    v-if="utils.permission.getUserPermissions()?.includes('*')"
    v-bind="props"
    :icon="props.icon"
    :title="props.title"
    :action="handleOpen"
  />
  <CoverGenerateModal
    v-if="open && utils.permission.getUserPermissions()?.includes('*')"
    :post-name="postName"
    @uploaded="handleCover"
    @applied="(attachment) => handleCover(attachment, true)"
    @close="open = false"
  />
</template>
