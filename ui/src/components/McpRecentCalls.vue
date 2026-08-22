<script setup lang="ts">
import {
  type ListMcpRecentCallsOutcomeEnum,
  McpRecentCallOutcomeEnum,
  type McpRecentCallOutcomeEnum as McpRecentCallOutcomeEnumType,
} from '@/api'
import { useAccessKeys } from '@/composables/useAccessKeys'
import { useRecentCalls } from '@/composables/useRecentCalls'
import { useTools } from '@/composables/useTools'
import {
  formatDuration,
  outcomeLabels,
  outcomeTagTheme,
  sourceLabels,
  sourceTagTheme,
} from '@/utils/recent-call'
import {
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPagination,
  VTag,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, ref, watch } from 'vue'

const { data: keys } = useAccessKeys()
const { data: tools } = useTools()

const page = ref(1)
const keyId = ref('')
const toolName = ref('')
const outcome = ref('')

watch([keyId, toolName, outcome], () => {
  page.value = 1
})

const query = computed(() => ({
  page: page.value,
  size: 20,
  keyId: keyId.value || undefined,
  toolName: toolName.value || undefined,
  outcome: (outcome.value || undefined) as ListMcpRecentCallsOutcomeEnum | undefined,
}))

const { data, isLoading, isError, error, refetch, isFetching } = useRecentCalls(query)

const outcomeOptions = computed(() => {
  return [
    {
      label: '全部',
      value: '',
    },
    ...Object.values(McpRecentCallOutcomeEnum).map((value) => ({
      value,
      label: outcomeLabels[value as McpRecentCallOutcomeEnumType],
    })),
  ]
})

function handleRefresh() {
  refetch()
}

function handlePageChange(nextPage: number) {
  page.value = nextPage
}

const keyFilterOptions = computed(() => {
  return [
    {
      label: '全部',
      value: '',
    },
    ...(keys.value?.map((key) => {
      return {
        label: `${key.displayName} (${key.keyPrefix}…)`,
        value: key.name,
      }
    }) || []),
  ]
})

const toolFilterOptions = computed(() => {
  return [
    {
      label: '全部',
      value: '',
    },
    ...(tools.value?.map((tool) => {
      return {
        label: tool.title || tool.name,
        value: tool.name,
      }
    }) || []),
  ]
})

const toolTitlesByName = computed(() => {
  return new Map(tools.value?.map((tool) => [tool.name, tool.title || tool.name]) || [])
})

function toolDisplayName(name: string) {
  return toolTitlesByName.value.get(name) || name
}
</script>

<template>
  <VCard :body-class="['!p-0']" title="最近调用">
    <template #actions>
      <div class=":uno: px-4">
        <VButton size="sm" :loading="isFetching" :disabled="isFetching" @click="handleRefresh">
          刷新
        </VButton>
      </div>
    </template>

    <div class=":uno: flex flex-col gap-4 p-4">
      <VAlert title="数据说明" type="info" :closable="false">
        <template #description>
          仅保留当前实例最近 500 条调用记录。Halo 或插件重启/重载后记录会清空。
        </template>
      </VAlert>

      <div class=":uno: flex flex-wrap items-center gap-3">
        <FilterDropdown v-model="keyId" label="密钥" :items="keyFilterOptions"></FilterDropdown>
        <FilterDropdown v-model="toolName" label="工具" :items="toolFilterOptions"></FilterDropdown>
        <FilterDropdown v-model="outcome" label="结果" :items="outcomeOptions"></FilterDropdown>
      </div>
    </div>

    <VLoading v-if="isLoading" />
    <div v-else-if="isError && !data?.items.length" class=":uno: px-4 py-3 text-sm text-red-600">
      加载失败：{{ error?.message || '无法获取最近调用，请稍后重试。' }}
    </div>
    <VEmpty
      v-else-if="!data?.items.length"
      title="暂无最近调用"
      message="当前实例尚未记录 MCP 工具调用，或筛选条件无匹配结果。"
    />
    <template v-else>
      <VEntityContainer>
        <VEntity v-for="call in data.items" :key="call.id">
          <template #start>
            <VEntityField
              :title="toolDisplayName(call.toolName)"
              :description="`${call.toolName} · ${call.keyDisplayName} · ${call.keyPrefix}… · ${call.ownerName}`"
            />
          </template>
          <template #end>
            <VEntityField
              v-tooltip="utils.date.format(call.startedAt)"
              :description="utils.date.timeAgo(call.startedAt)"
            ></VEntityField>
            <VEntityField :description="formatDuration(call.durationMillis)"></VEntityField>
            <VEntityField>
              <template #description>
                <VTag :theme="sourceTagTheme[call.sourceType]">
                  {{ sourceLabels[call.sourceType] }}
                </VTag>
              </template>
            </VEntityField>
            <VEntityField>
              <template #description>
                <VTag :theme="outcomeTagTheme[call.outcome]">
                  {{ outcomeLabels[call.outcome] }}
                </VTag>
              </template>
            </VEntityField>
          </template>
        </VEntity>
      </VEntityContainer>

      <div v-if="isError" class=":uno: border-t border-gray-100 px-4 py-2 text-xs text-red-600">
        加载失败：{{ error?.message || '无法获取最近调用，请稍后重试。' }}
      </div>
    </template>

    <template #footer>
      <VPagination
        v-model:page="page"
        :size="20"
        :total="data?.total || 0"
        :size-options="[20]"
        @update:page="handlePageChange"
      />
    </template>
  </VCard>
</template>
