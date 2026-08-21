<script setup lang="ts">
import { mcpClientGuides, type McpClientId } from '@/utils/mcp-config'
import { Toast } from '@halo-dev/components'
import { computed, shallowRef, type Component } from 'vue'
import ClaudeIcon from '~icons/simple-icons/claude'
import OpenaiIcon from '~icons/simple-icons/openai'
import CursorIcon from '~icons/simple-icons/cursor'
import VSCodeIcon from '~icons/logos/visual-studio-code'

const props = defineProps<{
  token?: string
}>()

const icons: Record<McpClientId, Component> = {
  'claude-code': ClaudeIcon,
  codex: OpenaiIcon,
  cursor: CursorIcon,
  vscode: VSCodeIcon,
}

const guides = computed(() => mcpClientGuides(props.token))
const active = shallowRef<McpClientId>('claude-code')
const current = computed(() => guides.value.find((guide) => guide.id === active.value)!)

async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    Toast.success('配置已复制')
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '复制失败')
  }
}
</script>

<template>
  <div>
    <div class=":uno: inline-flex flex-wrap gap-1 rounded-lg border border-gray-200 p-1">
      <button
        v-for="guide in guides"
        :key="guide.id"
        type="button"
        class="tab"
        :class="{ 'tab-active': active === guide.id }"
        @click="active = guide.id"
      >
        <component :is="icons[guide.id]" />
        {{ guide.label }}
      </button>
    </div>
    <div class=":uno: relative mt-3 rounded-md bg-gray-900 p-3 text-gray-100">
      <pre class=":uno: overflow-x-auto whitespace-pre pr-24 text-xs leading-5">{{
        current.content
      }}</pre>
      <div class=":uno: absolute right-2 top-2 flex items-center gap-2">
        <a
          v-if="current.installUrl"
          :href="current.installUrl"
          class=":uno: inline-flex items-center rounded-md bg-white px-2 py-1 text-xs text-gray-900 font-medium hover:bg-gray-200"
        >
          安装
        </a>
        <button
          type="button"
          class=":uno: inline-flex items-center rounded-md bg-white px-2 py-1 text-xs text-gray-900 font-medium hover:bg-gray-200"
          @click="copy(current.content)"
        >
          复制
        </button>
      </div>
    </div>
    <p v-if="!token" class=":uno: mt-2 text-xs text-gray-400">
      将配置中的 $HALO_MCP_TOKEN 替换为创建密钥时获得的完整密钥；创建或轮换密钥后可一键安装到 Cursor
      / VS Code。
    </p>
  </div>
</template>

<style scoped>
.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  border-radius: 0.375rem;
  padding: 0.375rem 0.75rem;
  font-size: 0.875rem;
  color: #4b5563;
}
.tab:hover {
  background-color: #f3f4f6;
}
.tab-active,
.tab-active:hover {
  background-color: #111827;
  color: #ffffff;
}
</style>
