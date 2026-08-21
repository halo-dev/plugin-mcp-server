import { mcpConsoleApiClient } from '@/api'
import { useQuery } from '@tanstack/vue-query'

export const QK_ACCESS_KEYS = 'plugin-mcp-server:access-keys'

export function useAccessKeys() {
  return useQuery({
    queryKey: [QK_ACCESS_KEYS],
    queryFn: async () => (await mcpConsoleApiClient.listMcpAccessKeys()).data,
  })
}
