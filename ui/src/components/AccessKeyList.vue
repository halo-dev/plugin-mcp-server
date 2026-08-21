<script setup lang="ts">
import AccessKeyCreationModal from '@/components/AccessKeyCreationModal.vue'
import AccessKeyListItem from '@/components/AccessKeyListItem.vue'
import { useAccessKeys } from '@/composables/useAccessKeys'
import { VButton, VCard, VEmpty, VEntityContainer, VLoading, VSpace } from '@halo-dev/components'
import { shallowRef } from 'vue'

const { data: keys, isLoading, isError, refetch } = useAccessKeys()

const creationModalVisible = shallowRef(false)
</script>

<template>
  <VCard :body-class="['!p-0']" title="MCP Keys">
    <template #actions>
      <div class=":uno: px-4">
        <VButton type="secondary" size="sm" @click="creationModalVisible = true">新建</VButton>
      </div>
    </template>
    <VLoading v-if="isLoading" />
    <VEmpty
      v-else-if="isError"
      title="加载失败"
      message="无法获取 MCP Key 列表，请检查网络后重试。"
    >
      <template #actions>
        <VButton type="primary" @click="refetch()">重试</VButton>
      </template>
    </VEmpty>
    <VEmpty
      v-else-if="!keys?.length"
      title="还没有 MCP Key"
      message="生成一个 Key，并为它选择允许调用的 Tool。"
    >
      <template #actions>
        <VSpace>
          <VButton type="secondary" @click="creationModalVisible = true">新建 Key</VButton>
          <VButton @click="refetch()">刷新</VButton>
        </VSpace>
      </template>
    </VEmpty>
    <VEntityContainer v-else>
      <AccessKeyListItem v-for="key in keys" :key="key.name" :mcp-access-key="key" />
    </VEntityContainer>
  </VCard>

  <AccessKeyCreationModal v-if="creationModalVisible" @close="creationModalVisible = false" />
</template>
