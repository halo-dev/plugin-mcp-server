<script setup lang="ts">
import { mcpConsoleApiClient, type UpdateMcpAccessKeyRequest } from '@/api'
import AccessKeyForm from '@/components/AccessKeyForm.vue'
import AccessKeySecretModal from '@/components/AccessKeySecretModal.vue'
import { QK_ACCESS_KEYS } from '@/composables/useAccessKeys'
import { useTools } from '@/composables/useTools'
import { Toast, VButton, VLoading, VModal, VSpace } from '@halo-dev/components'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { shallowRef, useTemplateRef } from 'vue'

const emit = defineEmits<{
  close: []
}>()

const queryClient = useQueryClient()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const form = useTemplateRef<InstanceType<typeof AccessKeyForm>>('form')
const createdToken = shallowRef('')

const { data: tools, isLoading } = useTools()

const { mutate, isLoading: submitting } = useMutation({
  mutationFn: async (input: UpdateMcpAccessKeyRequest) => {
    const { data } = await mcpConsoleApiClient.createMcpAccessKey({
      createMcpAccessKeyRequest: {
        displayName: input.displayName,
        allowedTools: input.allowedTools,
        expiresAt: input.expiresAt,
      },
    })
    return data
  },
  onSuccess: async (data) => {
    Toast.success('MCP 密钥已创建')
    await queryClient.invalidateQueries({ queryKey: [QK_ACCESS_KEYS] })
    createdToken.value = data.token
    modal.value?.close()
  },
  onError: (error) => {
    console.error('Failed to create MCP key', error)
    Toast.error('创建 MCP 密钥失败')
  },
})

function handleModalClose() {
  if (!createdToken.value) {
    emit('close')
  }
}
</script>

<template>
  <AccessKeySecretModal v-if="createdToken" :token="createdToken" @close="emit('close')" />
  <VModal
    v-else
    ref="modal"
    title="创建 MCP 密钥"
    :width="760"
    mount-to-body
    @close="handleModalClose"
  >
    <VLoading v-if="isLoading" />
    <AccessKeyForm v-else ref="form" :tools="tools ?? []" @submit="mutate" />
    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :loading="submitting"
          :disabled="submitting"
          @click="form?.submit()"
        >
          创建
        </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
