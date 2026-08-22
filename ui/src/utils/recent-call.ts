import { McpRecentCallOutcomeEnum, McpRecentCallSourceTypeEnum } from '@/api'
import type { TagTheme } from '@halo-dev/components'

export const outcomeLabels: Record<McpRecentCallOutcomeEnum, string> = {
  [McpRecentCallOutcomeEnum.Success]: '成功',
  [McpRecentCallOutcomeEnum.ToolError]: '工具错误',
  [McpRecentCallOutcomeEnum.ProtocolError]: '协议错误',
  [McpRecentCallOutcomeEnum.InternalError]: '内部错误',
  [McpRecentCallOutcomeEnum.Cancelled]: '已取消',
}

export const outcomeTagTheme: Record<McpRecentCallOutcomeEnum, TagTheme> = {
  [McpRecentCallOutcomeEnum.Success]: 'default',
  [McpRecentCallOutcomeEnum.ToolError]: 'danger',
  [McpRecentCallOutcomeEnum.ProtocolError]: 'danger',
  [McpRecentCallOutcomeEnum.InternalError]: 'danger',
  [McpRecentCallOutcomeEnum.Cancelled]: 'secondary',
}

export const sourceLabels: Record<McpRecentCallSourceTypeEnum, string> = {
  [McpRecentCallSourceTypeEnum.BuiltIn]: '内置',
  [McpRecentCallSourceTypeEnum.Plugin]: '插件',
  [McpRecentCallSourceTypeEnum.Unknown]: '未知',
}

export const sourceTagTheme: Record<McpRecentCallSourceTypeEnum, TagTheme> = {
  [McpRecentCallSourceTypeEnum.BuiltIn]: 'default',
  [McpRecentCallSourceTypeEnum.Plugin]: 'primary',
  [McpRecentCallSourceTypeEnum.Unknown]: 'secondary',
}

export function formatDuration(millis: number): string {
  if (millis < 1000) {
    return `${millis} ms`
  }
  return `${(millis / 1000).toFixed(2)} s`
}
