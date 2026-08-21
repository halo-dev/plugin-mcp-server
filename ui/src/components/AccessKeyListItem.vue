<script lang="ts" setup>
import { mcpConsoleApiClient, type McpAccessKey } from '@/api'
import { QK_ACCESS_KEYS } from '@/composables/useAccessKeys'
import {
  Dialog,
  Toast,
  VDropdownDivider,
  VDropdownItem,
  VEntity,
  VEntityField,
  VSwitch,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, shallowRef } from 'vue'
import AccessKeyEditingModal from './AccessKeyEditingModal.vue'
import AccessKeySecretModal from './AccessKeySecretModal.vue'

const props = defineProps<{
  mcpAccessKey: McpAccessKey
}>()

const queryClient = useQueryClient()

const expired = computed(
  () =>
    props.mcpAccessKey.expiresAt &&
    utils.date.dayjs(props.mcpAccessKey.expiresAt).isBefore(utils.date.dayjs()),
)

const editingModalVisible = shallowRef(false)
const rotatedToken = shallowRef('')

function invalidateAccessKeys() {
  return queryClient.invalidateQueries({ queryKey: [QK_ACCESS_KEYS] })
}

const { mutate: changeEnabled, isLoading: changingEnabled } = useMutation({
  mutationFn: async (enabled: boolean) => {
    await mcpConsoleApiClient.updateMcpAccessKey({
      name: props.mcpAccessKey.name,
      updateMcpAccessKeyRequest: {
        displayName: props.mcpAccessKey.displayName,
        allowedTools: props.mcpAccessKey.allowedTools,
        expiresAt: props.mcpAccessKey.expiresAt,
        enabled,
      },
    })
  },
  onSuccess: async (_, enabled) => {
    await invalidateAccessKeys()
    Toast.success(enabled ? 'MCP 密钥已启用' : 'MCP 密钥已禁用')
  },
  onError: (error) => {
    console.error('Failed to change MCP key status', error)
    Toast.error('更新 MCP 密钥状态失败')
  },
})

function handleRotate() {
  Dialog.warning({
    title: '轮换 MCP 密钥',
    description: '轮换后旧密钥会立即失效。确定继续吗？',
    confirmType: 'danger',
    confirmText: '轮换',
    onConfirm: async () => {
      try {
        const { data } = await mcpConsoleApiClient.rotateMcpAccessKey({
          name: props.mcpAccessKey.name,
        })
        await invalidateAccessKeys()
        rotatedToken.value = data.token
      } catch (error) {
        console.error('Failed to rotate MCP key', error)
        Toast.error('轮换 MCP 密钥失败')
        throw error
      }
    },
  })
}

function handleDelete() {
  Dialog.warning({
    title: '删除 MCP 密钥',
    description: `确定删除“${props.mcpAccessKey.displayName}”吗？使用该密钥的客户端将立即无法连接。`,
    confirmType: 'danger',
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await mcpConsoleApiClient.deleteMcpAccessKey({ name: props.mcpAccessKey.name })
        await invalidateAccessKeys()
        Toast.success('MCP 密钥已删除')
      } catch (error) {
        console.error('Failed to delete MCP key', error)
        Toast.error('删除 MCP 密钥失败')
        throw error
      }
    },
  })
}
</script>

<template>
  <VEntity>
    <template #start>
      <VEntityField
        :title="mcpAccessKey.displayName"
        :description="`${mcpAccessKey.keyPrefix}… · 创建人 ${mcpAccessKey.ownerName}`"
      />
    </template>
    <template #end>
      <div class=":uno: flex flex-wrap items-center justify-end gap-3 text-xs text-gray-500">
        <VTag>{{ mcpAccessKey.allowedTools.length }} 个工具</VTag>
        <span v-tooltip="utils.date.format(mcpAccessKey.creationTimestamp)">
          创建：{{
            mcpAccessKey.creationTimestamp
              ? utils.date.timeAgo(mcpAccessKey.creationTimestamp)
              : '-'
          }}
        </span>
        <span
          v-tooltip="{
            content: utils.date.format(mcpAccessKey.lastUsedAt),
            disabled: !mcpAccessKey.lastUsedAt,
          }"
        >
          最后使用：{{
            mcpAccessKey.lastUsedAt ? utils.date.timeAgo(mcpAccessKey.lastUsedAt) : '从未'
          }}
        </span>
        <span v-if="mcpAccessKey.expiresAt" :class="{ 'text-red-600': expired }">
          {{ expired ? '已过期' : '到期' }}：{{ utils.date.format(mcpAccessKey.expiresAt) }}
        </span>
        <VSwitch
          :model-value="mcpAccessKey.enabled"
          :loading="changingEnabled"
          :disabled="Boolean(mcpAccessKey.deletionTimestamp)"
          @change="changeEnabled"
        />
      </div>
    </template>
    <template #dropdownItems>
      <template v-if="!mcpAccessKey.deletionTimestamp">
        <VDropdownItem @click="editingModalVisible = true">编辑</VDropdownItem>
        <VDropdownItem @click="handleRotate">轮换密钥</VDropdownItem>
        <VDropdownDivider />
        <VDropdownItem type="danger" @click="handleDelete">删除</VDropdownItem>
      </template>
    </template>
  </VEntity>

  <AccessKeyEditingModal
    v-if="editingModalVisible"
    :access-key="mcpAccessKey"
    @close="editingModalVisible = false"
  />
  <AccessKeySecretModal v-if="rotatedToken" :token="rotatedToken" @close="rotatedToken = ''" />
</template>
