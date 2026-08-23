<script setup lang="ts">
import type { McpTool } from '@/api'
import McpToolCard from '@/components/McpToolCard.vue'
import McpToolDetailsModal from '@/components/McpToolDetailsModal.vue'
import { useTools } from '@/composables/useTools'
import { groupTools } from '@/utils/tool'
import { VCard, VTag } from '@halo-dev/components'
import { computed, shallowRef } from 'vue'

const { data: tools } = useTools()

const groups = computed(() => groupTools(tools.value ?? []))
const selectedTool = shallowRef<McpTool>()
</script>

<template>
  <VCard v-if="groups.length" title="已注册工具">
    <div class=":uno: flex flex-col divide-y divide-gray-100">
      <section
        v-for="group in groups"
        :key="group.source.pluginName"
        class=":uno: py-4 first:pt-0 last:pb-0"
      >
        <div class=":uno: mb-3 flex flex-wrap items-center gap-2">
          <span class=":uno: text-sm text-gray-900 font-medium">{{
            group.source.displayName
          }}</span>
          <VTag>{{ group.source.type === 'BUILT_IN' ? '内置' : '插件' }}</VTag>
          <span v-if="group.source.version" class=":uno: text-xs text-gray-500">
            v{{ group.source.version }}
          </span>
          <span class=":uno: text-xs text-gray-500">{{ group.toolCount }} 个工具</span>
        </div>
        <div class=":uno: flex flex-col gap-4">
          <div v-for="category in group.categories" :key="category.category">
            <div class=":uno: mb-2 flex items-center gap-2 text-xs text-gray-500 font-medium">
              <span>{{ category.label }}</span>
              <span>{{ category.tools.length }}</span>
            </div>
            <div class=":uno: grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              <McpToolCard
                v-for="tool in category.tools"
                :key="tool.name"
                :tool="tool"
                @activate="selectedTool = tool"
              />
            </div>
          </div>
        </div>
      </section>
    </div>
  </VCard>
  <McpToolDetailsModal v-if="selectedTool" :tool="selectedTool" @close="selectedTool = undefined" />
</template>
