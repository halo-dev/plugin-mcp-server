<script setup lang="ts">
import AccessKeyList from '@/components/AccessKeyList.vue'
import McpQuickStart from '@/components/McpQuickStart.vue'
import McpRecentCalls from '@/components/McpRecentCalls.vue'
import McpToolCatalog from '@/components/McpToolCatalog.vue'
import { VPageHeader } from '@halo-dev/components'
import McpLineIcon from '~icons/mingcute/mcp-line'
</script>

<template>
  <VPageHeader title="MCP 管理">
    <template #icon>
      <McpLineIcon />
    </template>
  </VPageHeader>

  <div class=":uno: m-0 flex flex-col gap-4 md:m-4">
    <McpQuickStart />
    <AccessKeyList />
    <McpRecentCalls />
    <McpToolCatalog />
  </div>
</template>
