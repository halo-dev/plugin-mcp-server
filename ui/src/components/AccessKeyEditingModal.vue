<script setup lang="ts">
import { mcpConsoleApiClient, type McpAccessKey, type UpdateMcpAccessKeyRequest } from '@/api'
import AccessKeyForm from '@/components/AccessKeyForm.vue'
import { QK_ACCESS_KEYS } from '@/composables/useAccessKeys'
import { useTools } from '@/composables/useTools'
import { Toast, VAlert, VButton, VLoading, VModal, VSpace } from '@halo-dev/components'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { useTemplateRef } from 'vue'

const props = defineProps<{
  accessKey: McpAccessKey
}>()

const emit = defineEmits<{
  close: []
}>()

const queryClient = useQueryClient()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const form = useTemplateRef<InstanceType<typeof AccessKeyForm>>('form')

const { data: tools, isLoading, isError, isFetching, refetch } = useTools()

const { mutate, isLoading: submitting } = useMutation({
  mutationFn: async (input: UpdateMcpAccessKeyRequest) => {
    await mcpConsoleApiClient.updateMcpAccessKey({
      name: props.accessKey.name,
      updateMcpAccessKeyRequest: input,
    })
  },
  onSuccess: async () => {
    Toast.success('MCP 密钥已更新')
    await queryClient.invalidateQueries({ queryKey: [QK_ACCESS_KEYS] })
    modal.value?.close()
  },
  onError: (error) => {
    console.error('Failed to update MCP key', error)
    Toast.error('更新 MCP 密钥失败')
  },
})
</script>

<template>
  <VModal ref="modal" title="编辑 MCP 密钥" :width="1000" mount-to-body @close="emit('close')">
    <VLoading v-if="isLoading" />
    <VAlert
      v-else-if="isError"
      type="error"
      title="工具目录加载失败"
      description="无法安全编辑密钥权限，请重试。"
      :closable="false"
    >
      <template #actions>
        <VButton size="sm" :loading="isFetching" @click="refetch()">重试</VButton>
      </template>
    </VAlert>
    <AccessKeyForm
      v-else
      ref="form"
      :access-key="accessKey"
      :tools="tools ?? []"
      @submit="mutate"
    />
    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :loading="submitting"
          :disabled="submitting || isLoading || isError"
          @click="form?.submit()"
        >
          保存
        </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
