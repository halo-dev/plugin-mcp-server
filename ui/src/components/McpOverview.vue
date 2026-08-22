<script setup lang="ts">
import { McpRecentCallOutcomeEnum } from '@/api'
import { useAccessKeys } from '@/composables/useAccessKeys'
import { useRecentCalls } from '@/composables/useRecentCalls'
import { useTools } from '@/composables/useTools'
import { VAlert, VCard } from '@halo-dev/components'
import { computed } from 'vue'
import KeyIcon from '~icons/mingcute/key-2-line'
import ToolIcon from '~icons/mingcute/tool-line'
import FlashIcon from '~icons/mingcute/flash-line'
import CheckCircleIcon from '~icons/mingcute/check-circle-line'

const { data: keys } = useAccessKeys()
const { data: tools } = useTools()

const totalQuery = computed(() => ({ page: 1, size: 1 }))
const successQuery = computed(() => ({
  page: 1,
  size: 1,
  outcome: McpRecentCallOutcomeEnum.Success,
}))
const { data: recentTotal } = useRecentCalls(totalQuery)
const { data: recentSuccess } = useRecentCalls(successQuery)

const enabledKeyCount = computed(
  () => keys.value?.filter((key) => key.enabled && !key.deletionTimestamp).length ?? 0,
)

const toolStats = computed(() => {
  const list = tools.value ?? []
  return {
    total: list.length,
    readOnly: list.filter((tool) => !tool.destructive && tool.readOnly).length,
    destructive: list.filter((tool) => tool.destructive).length,
    write: list.filter((tool) => !tool.destructive && !tool.readOnly).length,
  }
})

function toolSegmentWidth(count: number) {
  if (!toolStats.value.total) {
    return '0%'
  }
  return `${(count / toolStats.value.total) * 100}%`
}

const enabledKeyWidth = computed(() => {
  if (!keys.value?.length) {
    return '0%'
  }
  return `${(enabledKeyCount.value / keys.value.length) * 100}%`
})

const callTotal = computed(() => recentTotal.value?.total ?? 0)
const callSuccess = computed(() => recentSuccess.value?.total ?? 0)
function callSegmentWidth(count: number) {
  if (!callTotal.value) {
    return '0%'
  }
  return `${(count / callTotal.value) * 100}%`
}

const successRateValue = computed(() => {
  if (!callTotal.value) {
    return 0
  }
  return Math.round((callSuccess.value / callTotal.value) * 100)
})
const successRate = computed(() => (callTotal.value ? `${successRateValue.value}%` : '–'))
</script>

<template>
  <VCard title="概览">
    <div class=":uno: flex flex-col gap-4">
      <VAlert v-if="keys && !keys.length" title="开始使用" type="info" :closable="false">
        <template #description>
          尚未创建 MCP 访问密钥。创建密钥后，即可在 Claude Code、Codex、Cursor、VS Code
          等客户端中接入 Halo。
        </template>
      </VAlert>

      <div class=":uno: grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <div class=":uno: flex items-center gap-3 rounded-md border border-gray-100 p-3">
          <div
            class=":uno: flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-500"
          >
            <KeyIcon class=":uno: h-5 w-5" />
          </div>
          <div class=":uno: min-w-0 flex-1">
            <div class=":uno: text-xs text-gray-400">访问密钥</div>
            <div class=":uno: mt-0.5 text-xl text-gray-900 font-semibold leading-6">
              {{ keys?.length ?? '–' }}
            </div>
            <div class=":uno: mt-0.5 truncate text-xs text-gray-500">
              {{ enabledKeyCount }} 个已启用
            </div>
            <div
              v-if="keys?.length"
              class=":uno: mt-1.5 flex h-1.5 overflow-hidden rounded-full bg-gray-100"
            >
              <div class=":uno: bg-emerald-400" :style="{ width: enabledKeyWidth }" />
              <div class=":uno: flex-1 bg-gray-200" />
            </div>
          </div>
        </div>

        <div class=":uno: flex items-center gap-3 rounded-md border border-gray-100 p-3">
          <div
            class=":uno: flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-500"
          >
            <ToolIcon class=":uno: h-5 w-5" />
          </div>
          <div class=":uno: min-w-0 flex-1">
            <div class=":uno: text-xs text-gray-400">已注册工具</div>
            <div class=":uno: mt-0.5 text-xl text-gray-900 font-semibold leading-6">
              {{ tools ? toolStats.total : '–' }}
            </div>
            <div class=":uno: mt-0.5 truncate text-xs text-gray-500">
              只读 {{ toolStats.readOnly }} · 写入 {{ toolStats.write }} · 破坏性
              {{ toolStats.destructive }}
            </div>
            <div
              v-if="toolStats.total"
              class=":uno: mt-1.5 flex h-1.5 overflow-hidden rounded-full bg-gray-100"
            >
              <div
                class=":uno: bg-gray-300"
                :style="{ width: toolSegmentWidth(toolStats.readOnly) }"
              />
              <div
                class=":uno: bg-blue-400"
                :style="{ width: toolSegmentWidth(toolStats.write) }"
              />
              <div
                class=":uno: bg-red-400"
                :style="{ width: toolSegmentWidth(toolStats.destructive) }"
              />
            </div>
          </div>
        </div>

        <div class=":uno: flex items-center gap-3 rounded-md border border-gray-100 p-3">
          <div
            class=":uno: flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-500"
          >
            <FlashIcon class=":uno: h-5 w-5" />
          </div>
          <div class=":uno: min-w-0 flex-1">
            <div class=":uno: text-xs text-gray-400">最近调用</div>
            <div class=":uno: mt-0.5 text-xl text-gray-900 font-semibold leading-6">
              {{ recentTotal ? callTotal : '–' }}
            </div>
            <div class=":uno: mt-0.5 truncate text-xs text-gray-500">成功 {{ callSuccess }} 次</div>
            <div
              v-if="callTotal"
              class=":uno: mt-1.5 flex h-1.5 overflow-hidden rounded-full bg-gray-100"
            >
              <div class=":uno: bg-emerald-400" :style="{ width: callSegmentWidth(callSuccess) }" />
              <div
                class=":uno: bg-red-400"
                :style="{ width: callSegmentWidth(callTotal - callSuccess) }"
              />
            </div>
          </div>
        </div>

        <div class=":uno: flex items-center gap-3 rounded-md border border-gray-100 p-3">
          <div
            class=":uno: flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-500"
          >
            <CheckCircleIcon class=":uno: h-5 w-5" />
          </div>
          <div class=":uno: min-w-0 flex-1">
            <div class=":uno: text-xs text-gray-400">调用成功率</div>
            <div class=":uno: mt-0.5 text-xl text-gray-900 font-semibold leading-6">
              {{ successRate }}
            </div>
            <div class=":uno: mt-0.5 truncate text-xs text-gray-500">
              {{ callTotal ? `共 ${callTotal} 次调用` : '暂无调用记录' }}
            </div>
            <div
              v-if="callTotal"
              class=":uno: mt-1.5 h-1.5 overflow-hidden rounded-full bg-gray-100"
            >
              <div
                class=":uno: h-full rounded-full bg-emerald-500"
                :style="{ width: `${successRateValue}%` }"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  </VCard>
</template>
