<script setup lang="ts">
import type { McpTool } from '@/api'
import JsonSchemaViewer from '@/components/JsonSchemaViewer.vue'
import { toolCategoryLabel } from '@/utils/tool'
import { VButton, VModal, VSpace, VTag } from '@halo-dev/components'
import { useTemplateRef } from 'vue'

defineProps<{
  tool: McpTool
}>()

const emit = defineEmits<{
  close: []
}>()

const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
</script>

<template>
  <VModal
    ref="modal"
    :title="tool.title || tool.name"
    :width="900"
    mount-to-body
    layer-closable
    :centered="false"
    @close="emit('close')"
  >
    <div class=":uno: flex flex-col gap-6">
      <section>
        <div class=":uno: flex flex-wrap items-center gap-2">
          <code class=":uno: break-all text-sm text-gray-700">{{ tool.name }}</code>
          <VTag>{{ tool.source.type === 'BUILT_IN' ? '内置' : '插件' }}</VTag>
          <VTag v-if="tool.destructive" theme="danger">破坏性</VTag>
          <VTag v-else-if="tool.readOnly">只读</VTag>
          <VTag v-else>写入</VTag>
        </div>
        <p
          v-if="tool.description"
          class=":uno: mt-3 whitespace-pre-wrap text-sm text-gray-600 leading-6"
        >
          {{ tool.description }}
        </p>
      </section>

      <dl class=":uno: grid gap-4 rounded-md bg-gray-50 p-4 sm:grid-cols-2">
        <div>
          <dt class=":uno: text-xs text-gray-500">来源</dt>
          <dd class=":uno: mt-1 text-sm text-gray-800">
            {{ tool.source.displayName }}
            <span v-if="tool.source.version" class=":uno: text-gray-500">
              v{{ tool.source.version }}
            </span>
          </dd>
        </div>
        <div>
          <dt class=":uno: text-xs text-gray-500">分类</dt>
          <dd class=":uno: mt-1 text-sm text-gray-800">{{ toolCategoryLabel(tool.category) }}</dd>
        </div>
      </dl>

      <JsonSchemaViewer
        title="Input Schema"
        :schema="tool.inputSchema"
        empty-message="该工具未声明输入 Schema"
      />
      <JsonSchemaViewer
        title="Output Schema"
        :schema="tool.outputSchema"
        empty-message="该工具未声明输出 Schema"
      />
    </div>
    <template #footer>
      <VSpace>
        <VButton @click="modal?.close()">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
