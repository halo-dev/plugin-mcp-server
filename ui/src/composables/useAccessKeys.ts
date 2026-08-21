import { mcpConsoleApiClient, type McpAccessKey } from '@/api'
import { useQuery } from '@tanstack/vue-query'

export const QK_ACCESS_KEYS = 'plugin-mcp-server:access-keys'

export function accessKeyRefetchInterval(keys?: McpAccessKey[]) {
  return keys?.some((key) => Boolean(key.deletionTimestamp)) ? 1000 : false
}

export function useAccessKeys() {
  return useQuery({
    queryKey: [QK_ACCESS_KEYS],
    queryFn: async () => (await mcpConsoleApiClient.listMcpAccessKeys()).data,
    refetchInterval: accessKeyRefetchInterval,
  })
}
