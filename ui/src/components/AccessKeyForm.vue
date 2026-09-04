<script setup lang="ts">
import type { McpAccessKey, McpTool, UpdateMcpAccessKeyRequest } from '@/api'
import McpToolCard from '@/components/McpToolCard.vue'
import { groupTools } from '@/utils/tool'
import { submitForm } from '@formkit/core'
import { VAlert, VButton, VSpace, VTag } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, reactive, shallowRef } from 'vue'

const props = defineProps<{
  accessKey?: McpAccessKey
  tools: McpTool[]
}>()

const emit = defineEmits<{
  submit: [input: UpdateMcpAccessKeyRequest]
}>()

const ALL_TOOLS = '*'
const formId = `mcp-access-key-form-${props.accessKey?.name ?? 'new'}`
const displayName = shallowRef(props.accessKey?.displayName ?? '')
const expiresAt = shallowRef(
  props.accessKey?.expiresAt ? utils.date.toDatetimeLocal(props.accessKey.expiresAt) : '',
)
const enabled = shallowRef(props.accessKey?.enabled ?? true)
const allowedIpRanges = shallowRef((props.accessKey?.allowedIpRanges ?? []).join('\n'))
const allowAllTools = shallowRef(props.accessKey?.allowedTools.includes(ALL_TOOLS) ?? false)
const selected = reactive<Record<string, boolean>>(
  Object.fromEntries(
    props.tools.map((tool) => [
      tool.name,
      props.accessKey?.allowedTools.includes(tool.name) ?? false,
    ]),
  ),
)

const groups = computed(() => groupTools(props.tools))
const availableNames = new Set(props.tools.map((tool) => tool.name))
const unavailableAllowedTools = shallowRef(
  (props.accessKey?.allowedTools ?? []).filter(
    (name) => name !== ALL_TOOLS && !availableNames.has(name),
  ),
)

const selectedCount = computed(() => props.tools.filter((tool) => selected[tool.name]).length)

function applyPreset(preset: 'read' | 'content' | 'all' | 'none') {
  if (preset === 'none') {
    unavailableAllowedTools.value = []
  }
  for (const tool of props.tools) {
    selected[tool.name] =
      preset === 'all' ||
      (preset === 'read' && tool.readOnly) ||
      (preset === 'content' && tool.source.type === 'BUILT_IN' && !tool.destructive)
  }
}

function removeUnavailableTool(name: string) {
  unavailableAllowedTools.value = unavailableAllowedTools.value.filter((item) => item !== name)
}

function onSubmit() {
  emit('submit', {
    displayName: displayName.value,
    allowedIpRanges: [
      ...new Set(
        allowedIpRanges.value
          .split(/\r?\n/)
          .map((range) => range.trim())
          .filter(Boolean),
      ),
    ],
    allowedTools: allowAllTools.value
      ? [ALL_TOOLS]
      : [
          ...unavailableAllowedTools.value,
          ...props.tools.filter((tool) => selected[tool.name]).map((tool) => tool.name),
        ],
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
    <FormKit
      v-model="allowedIpRanges"
      type="textarea"
      name="allowedIpRanges"
      label="允许访问的 IP"
      help="每行填写一个 IPv4、IPv6 或 CIDR；留空表示不限制。使用反向代理时需确保客户端来源地址可信。"
      :placeholder="'203.0.113.10\n203.0.113.0/24\n2001:db8::/32'"
      :rows="5"
    />
    <FormKit
      v-model="allowAllTools"
      type="switch"
      name="allowAllTools"
      label="自动允许所有工具"
      help="启用后，当前及后续新增的工具都会自动生效。"
    />
    <VAlert
      v-if="allowAllTools"
      type="warning"
      title="此密钥将自动获得当前及未来新增的全部工具权限"
      description="包括第三方插件提供的工具，以及可能修改或删除数据的高风险工具。新增工具不会再次请求确认，请仅用于完全可信的客户端，并建议同时限制访问 IP 和过期时间。"
      :closable="false"
    />

    <div v-if="!allowAllTools" class=":uno: mt-5 border-t border-gray-100 pt-5">
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
          <div v-if="unavailableAllowedTools.length" class=":uno: mt-2 text-xs text-amber-600">
            <div class=":uno: mb-1">暂不可用的既有工具授权默认保留，可单独移除：</div>
            <div
              v-for="name in unavailableAllowedTools"
              :key="name"
              class=":uno: flex items-center gap-2"
            >
              <code>{{ name }}</code>
              <VButton
                size="sm"
                :aria-label="`移除暂不可用工具授权 ${name}`"
                @click="removeUnavailableTool(name)"
              >
                移除
              </VButton>
            </div>
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
