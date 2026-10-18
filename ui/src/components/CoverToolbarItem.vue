<script lang="ts" setup>
import { ToolbarItem, type ToolbarItemComponentProps } from '@halo-dev/richtext-editor'
import { ref } from 'vue'
import CoverGenerateModal from '@/components/CoverGenerateModal.vue'

const props = defineProps<ToolbarItemComponentProps>()

const open = ref(false)

function handleOpen() {
  open.value = true
}
</script>

<template>
  <ToolbarItem v-bind="props" :icon="props.icon" :title="props.title" :action="handleOpen" />
  <CoverGenerateModal v-if="open" @close="open = false" />
</template>
