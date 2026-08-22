import {
  mcpConsoleApiClient,
  type ListMcpRecentCallsOutcomeEnum,
  type McpRecentCallPage,
} from '@/api'
import { useDocumentVisibility } from '@vueuse/core'
import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'

export const QK_RECENT_CALLS = 'plugin-mcp-server:recent-calls'

export interface RecentCallQuery {
  page: number
  size: number
  keyId?: string
  toolName?: string
  outcome?: ListMcpRecentCallsOutcomeEnum
}

export function useRecentCalls(query: Ref<RecentCallQuery>) {
  const visibility = useDocumentVisibility()
  const queryKey = computed(() => [QK_RECENT_CALLS, query.value])

  return useQuery<McpRecentCallPage, Error>({
    queryKey,
    queryFn: async () => {
      const { data } = await mcpConsoleApiClient.listMcpRecentCalls(query.value)
      return data
    },
    refetchInterval: () => (visibility.value === 'visible' ? 5000 : false),
    keepPreviousData: true,
  })
}
