import { mcpConsoleApiClient } from '@/api'
import { useQuery } from '@tanstack/vue-query'

export const QK_TOOLS = 'plugin-mcp-server:tools'

export function useTools() {
  return useQuery({
    queryKey: [QK_TOOLS],
    queryFn: async () => (await mcpConsoleApiClient.listMcpTools()).data,
  })
}
