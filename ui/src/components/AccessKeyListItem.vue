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
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { useQueryClient } from '@tanstack/vue-query'
import { computed, shallowRef } from 'vue'
import AccessKeyEditingModal from './AccessKeyEditingModal.vue'
import AccessKeySecretModal from './AccessKeySecretModal.vue'

const props = defineProps<{
  mcpAccessKey: McpAccessKey
}>()

const queryClient = useQueryClient()

const status = computed(() => {
  if (!props.mcpAccessKey.enabled) {
    return { state: 'warning' as const, text: '已禁用' }
  }
  if (
    props.mcpAccessKey.expiresAt &&
    utils.date.dayjs(props.mcpAccessKey.expiresAt).isBefore(utils.date.dayjs())
  ) {
    return { state: 'warning' as const, text: '已过期' }
  }
  return { state: 'success' as const, text: '可用' }
})

const editingModalVisible = shallowRef(false)
const rotatedToken = shallowRef('')

function invalidateAccessKeys() {
  return queryClient.invalidateQueries({ queryKey: [QK_ACCESS_KEYS] })
}

function handleRotate() {
  Dialog.warning({
    title: '轮换 MCP Key',
    description: '轮换后旧 Key 会立即失效。确定继续吗？',
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
        Toast.error('轮换 MCP Key 失败')
        throw error
      }
    },
  })
}

function handleDelete() {
  Dialog.warning({
    title: '删除 MCP Key',
    description: `确定删除“${props.mcpAccessKey.displayName}”吗？使用该 Key 的客户端将立即无法连接。`,
    confirmType: 'danger',
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await mcpConsoleApiClient.deleteMcpAccessKey({ name: props.mcpAccessKey.name })
        // 后端删除是异步的，立即刷新仍会返回该 Key，先从缓存中移除，稍后再同步
        queryClient.setQueryData<McpAccessKey[]>([QK_ACCESS_KEYS], (old) =>
          old?.filter((key) => key.name !== props.mcpAccessKey.name),
        )
        setTimeout(invalidateAccessKeys, 3000)
        Toast.success('MCP Key 已删除')
      } catch (error) {
        console.error('Failed to delete MCP key', error)
        Toast.error('删除 MCP Key 失败')
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
        <VTag>{{ mcpAccessKey.allowedTools.length }} 个 Tool</VTag>
        <span
          >创建：{{
            mcpAccessKey.creationTimestamp
              ? utils.date.timeAgo(mcpAccessKey.creationTimestamp)
              : '-'
          }}</span
        >
        <span
          >最后使用：{{
            mcpAccessKey.lastUsedAt ? utils.date.timeAgo(mcpAccessKey.lastUsedAt) : '从未'
          }}</span
        >
        <span v-if="mcpAccessKey.expiresAt">
          到期：{{ utils.date.format(mcpAccessKey.expiresAt) }}
        </span>
        <VStatusDot :state="status.state" :text="status.text" />
      </div>
    </template>
    <template #dropdownItems>
      <VDropdownItem @click="editingModalVisible = true">编辑</VDropdownItem>
      <VDropdownItem @click="handleRotate">轮换 Key</VDropdownItem>
      <VDropdownDivider />
      <VDropdownItem type="danger" @click="handleDelete">删除</VDropdownItem>
    </template>
  </VEntity>

  <AccessKeyEditingModal
    v-if="editingModalVisible"
    :access-key="mcpAccessKey"
    @close="editingModalVisible = false"
  />
  <AccessKeySecretModal v-if="rotatedToken" :token="rotatedToken" @close="rotatedToken = ''" />
</template>
