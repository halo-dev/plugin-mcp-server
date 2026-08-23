<script setup lang="ts">
import type { McpTool } from '@/api'
import { VTag } from '@halo-dev/components'
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    selectable?: boolean
    tool: McpTool
  }>(),
  {
    selectable: false,
  },
)

const selected = defineModel<boolean>({ default: false })

const emit = defineEmits<{
  activate: []
}>()

const cardClass = computed(() => {
  if (!props.selectable) {
    return 'border-transparent hover:bg-gray-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'
  }

  return selected.value
    ? 'cursor-pointer border border-[rgb(var(--colors-primary))]'
    : 'cursor-pointer border border-gray-100 hover:border-gray-200 hover:bg-gray-100'
})

function handleClick() {
  if (!props.selectable) {
    emit('activate')
  }
}
</script>

<template>
  <component
    :is="selectable ? 'label' : 'button'"
    :type="selectable ? undefined : 'button'"
    class=":uno: flex w-full items-start gap-2.5 rounded-lg bg-gray-50 p-3 text-left transition-colors"
    :class="cardClass"
    :aria-label="selectable ? undefined : `查看 ${tool.title || tool.name} 的详情`"
    @click="handleClick"
  >
    <input
      v-if="selectable"
      v-model="selected"
      type="checkbox"
      class=":uno: mt-0.5 h-4 w-4 shrink-0 accent-[rgb(var(--colors-primary))]"
    />
    <div class=":uno: min-w-0 flex-1">
      <div class=":uno: flex items-start justify-between gap-2">
        <div class=":uno: min-w-0">
          <div class=":uno: truncate text-sm text-gray-800 font-medium">
            {{ tool.title || tool.name }}
          </div>
          <code class=":uno: mt-1 block break-all text-xs text-gray-500">{{ tool.name }}</code>
        </div>
        <VTag v-if="tool.destructive" theme="danger">破坏性</VTag>
        <VTag v-else-if="tool.readOnly">只读</VTag>
        <VTag v-else>写入</VTag>
      </div>
      <p v-if="tool.description" class=":uno: mt-2 text-xs text-gray-500 leading-5">
        {{ tool.description }}
      </p>
    </div>
  </component>
</template>
