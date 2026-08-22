import { axiosInstance } from '@halo-dev/api-client'
import { ConsoleApiMcpHaloRunV1alpha1McpAccessKeyApi } from './generated'

export const mcpConsoleApiClient = new ConsoleApiMcpHaloRunV1alpha1McpAccessKeyApi(
  undefined,
  '',
  axiosInstance,
)

export {
  ListMcpRecentCallsOutcomeEnum,
  McpRecentCallOutcomeEnum,
  McpRecentCallSourceTypeEnum,
} from './generated'

export type {
  CreateMcpAccessKeyRequest,
  CreatedMcpAccessKey,
  McpAccessKey,
  McpRecentCall,
  McpRecentCallPage,
  McpTool,
  McpToolSource,
  UpdateMcpAccessKeyRequest,
} from './generated'
