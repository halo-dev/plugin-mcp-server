<script setup lang="ts">
import { useTools } from '@/composables/useTools'
import { groupTools } from '@/utils/tool'
import { VCard, VTag } from '@halo-dev/components'
import { computed } from 'vue'

const { data: tools } = useTools()

const groups = computed(() => groupTools(tools.value ?? []))
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
          <span v-if="group.source.version" class=":uno: text-xs text-gray-400">
            v{{ group.source.version }}
          </span>
          <span class=":uno: text-xs text-gray-400">{{ group.toolCount }} 个工具</span>
        </div>
        <div class=":uno: flex flex-col gap-4">
          <div v-for="category in group.categories" :key="category.category">
            <div class=":uno: mb-2 flex items-center gap-2 text-xs text-gray-500 font-medium">
              <span>{{ category.label }}</span>
              <span>{{ category.tools.length }}</span>
            </div>
            <div class=":uno: grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              <div
                v-for="tool in category.tools"
                :key="tool.name"
                class=":uno: rounded-md border border-gray-100 p-3"
              >
                <div class=":uno: flex items-start justify-between gap-2">
                  <div class=":uno: min-w-0">
                    <div class=":uno: truncate text-sm text-gray-800 font-medium">
                      {{ tool.title || tool.name }}
                    </div>
                    <code class=":uno: mt-1 block break-all text-xs text-gray-400">{{
                      tool.name
                    }}</code>
                  </div>
                  <VTag v-if="tool.destructive" theme="danger">破坏性</VTag>
                  <VTag v-else-if="tool.readOnly">只读</VTag>
                  <VTag v-else>写入</VTag>
                </div>
                <p v-if="tool.description" class=":uno: mt-2 text-xs text-gray-500 leading-5">
                  {{ tool.description }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </VCard>
</template>
