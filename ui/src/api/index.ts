import { axiosInstance } from '@halo-dev/api-client'
import { ConsoleApiMcpHaloRunV1alpha1McpAccessKeyApi } from './generated'

export const mcpConsoleApiClient = new ConsoleApiMcpHaloRunV1alpha1McpAccessKeyApi(
  undefined,
  '',
  axiosInstance,
)

export type {
  CreateMcpAccessKeyRequest,
  CreatedMcpAccessKey,
  McpAccessKey,
  McpTool,
  McpToolSource,
  UpdateMcpAccessKeyRequest,
} from './generated'
