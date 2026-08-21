<script setup lang="ts">
import McpConnectionGuide from '@/components/McpConnectionGuide.vue'
import { VAlert, VButton, VModal, VSpace } from '@halo-dev/components'
import { useClipboard } from '@vueuse/core'
import { useTemplateRef } from 'vue'

const { token } = defineProps<{
  token: string
}>()

const emit = defineEmits<{
  close: []
}>()

const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')

const { copy, copied } = useClipboard({
  source: token,
  legacy: true,
})
</script>

<template>
  <VModal ref="modal" title="保存 MCP 密钥" :width="640" mount-to-body @close="emit('close')">
    <div class=":uno: flex flex-col gap-4">
      <VAlert
        type="warning"
        title="请立即保存并妥善保管"
        description="完整密钥只显示这一次，关闭后无法再次查看，只能轮换生成新的密钥。千万不要泄露给任何人。"
        :closable="false"
      />
      <FormKit type="textarea" :model-value="token" label="MCP 密钥" :rows="4" readonly />
      <div>
        <div class=":uno: mb-2 text-sm text-gray-700 font-medium">接入方式</div>
        <McpConnectionGuide :token="token" />
      </div>
    </div>
    <template #footer>
      <VSpace>
        <VButton type="secondary" @click="copy">{{ copied ? '已复制' : '复制密钥' }}</VButton>
        <VButton @click="modal?.close()">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
