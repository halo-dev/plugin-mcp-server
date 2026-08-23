<script setup lang="ts">
import type { McpAccessKey, McpTool, UpdateMcpAccessKeyRequest } from '@/api'
import McpToolCard from '@/components/McpToolCard.vue'
import { groupTools } from '@/utils/tool'
import { submitForm } from '@formkit/core'
import { VButton, VSpace, VTag } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, reactive, shallowRef } from 'vue'

const props = defineProps<{
  accessKey?: McpAccessKey
  tools: McpTool[]
}>()

const emit = defineEmits<{
  submit: [input: UpdateMcpAccessKeyRequest]
}>()

const formId = `mcp-access-key-form-${props.accessKey?.name ?? 'new'}`
const displayName = shallowRef(props.accessKey?.displayName ?? '')
const expiresAt = shallowRef(
  props.accessKey?.expiresAt ? utils.date.toDatetimeLocal(props.accessKey.expiresAt) : '',
)
const enabled = shallowRef(props.accessKey?.enabled ?? true)
const selected = reactive<Record<string, boolean>>(
  Object.fromEntries(
    props.tools.map((tool) => [
      tool.name,
      props.accessKey?.allowedTools.includes(tool.name) ?? false,
    ]),
  ),
)

const groups = computed(() => groupTools(props.tools))

const selectedCount = computed(() => props.tools.filter((tool) => selected[tool.name]).length)

function applyPreset(preset: 'read' | 'content' | 'all' | 'none') {
  for (const tool of props.tools) {
    selected[tool.name] =
      preset === 'all' ||
      (preset === 'read' && tool.readOnly) ||
      (preset === 'content' && tool.source.type === 'BUILT_IN' && !tool.destructive)
  }
}

function onSubmit() {
  emit('submit', {
    displayName: displayName.value,
    allowedTools: props.tools.filter((tool) => selected[tool.name]).map((tool) => tool.name),
    expiresAt: expiresAt.value ? utils.date.toISOString(expiresAt.value) : undefined,
    enabled: enabled.value,
  })
}

defineExpose({
  submit: () => submitForm(formId),
})
</script>

<template>
  <FormKit :id="formId" type="form" :actions="false" @submit="onSubmit">
    <FormKit
      v-model="displayName"
      type="text"
      name="displayName"
      label="名称"
      placeholder="例如：内容自动化"
      validation="required|length:1,100"
    />
    <FormKit
      v-model="expiresAt"
      type="datetime-local"
      name="expiresAt"
      label="过期时间"
      help="留空表示永不过期"
    />
    <FormKit v-if="accessKey" v-model="enabled" type="switch" name="enabled" label="启用" />

    <div class=":uno: mt-5 border-t border-gray-100 pt-5">
      <div class=":uno: mb-3 flex flex-wrap items-center justify-between gap-3">
        <div>
          <div class=":uno: flex items-center gap-2 text-sm text-gray-900 font-medium">
            <span>可用工具</span>
            <span class=":uno: text-xs text-gray-500 font-normal">
              已选 {{ selectedCount }}/{{ tools.length }}
            </span>
          </div>
          <div class=":uno: mt-1 text-xs text-gray-500">
            新增工具不会自动授予现有密钥；「内容管理」预设不含破坏性工具。
          </div>
        </div>
        <VSpace>
          <VButton size="sm" @click="applyPreset('read')">只读</VButton>
          <VButton size="sm" @click="applyPreset('content')">内容管理</VButton>
          <VButton size="sm" @click="applyPreset('all')">全部</VButton>
          <VButton size="sm" @click="applyPreset('none')">清空</VButton>
        </VSpace>
      </div>

      <div class=":uno: flex flex-col gap-6">
        <section v-for="group in groups" :key="group.source.pluginName">
          <div class=":uno: mb-3 flex items-center gap-2 text-sm text-gray-900 font-medium">
            <span>{{ group.source.displayName }}</span>
            <VTag>{{ group.source.type === 'BUILT_IN' ? '内置' : '插件' }}</VTag>
          </div>
          <div class=":uno: flex flex-col gap-4">
            <div v-for="category in group.categories" :key="category.category">
              <div class=":uno: mb-2 flex items-center gap-2 text-xs text-gray-500 font-medium">
                <span>{{ category.label }}</span>
                <span>{{ category.tools.length }}</span>
              </div>
              <div class=":uno: grid grid-cols-1 gap-3 sm:grid-cols-2">
                <McpToolCard
                  v-for="tool in category.tools"
                  :key="tool.name"
                  v-model="selected[tool.name]"
                  selectable
                  :tool="tool"
                />
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </FormKit>
</template>
