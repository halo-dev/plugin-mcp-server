<script setup lang="ts">
import McpConnectionGuide from '@/components/McpConnectionGuide.vue'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { useTemplateRef } from 'vue'

const props = defineProps<{
  token: string
}>()

const emit = defineEmits<{
  close: []
}>()

const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')

async function copyToken() {
  try {
    await navigator.clipboard.writeText(props.token)
    Toast.success('MCP Key 已复制')
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '复制失败')
  }
}
</script>

<template>
  <VModal ref="modal" title="保存 MCP Key" :width="640" mount-to-body @close="emit('close')">
    <div class=":uno: flex flex-col gap-4">
      <div class=":uno: rounded-md bg-amber-50 px-4 py-3 text-sm text-amber-800">
        完整 Key 只显示这一次。关闭后无法再次查看，只能轮换生成新的 Key。
      </div>
      <FormKit type="textarea" :model-value="token" label="MCP Key" :rows="4" readonly />
      <div>
        <div class=":uno: mb-2 text-sm text-gray-700 font-medium">接入方式</div>
        <McpConnectionGuide :token="token" />
      </div>
    </div>
    <template #footer>
      <VSpace>
        <VButton @click="modal?.close()">关闭</VButton>
        <VButton type="secondary" @click="copyToken">复制 Key</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
