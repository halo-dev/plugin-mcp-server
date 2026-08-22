import { McpRecentCallOutcomeEnum, McpRecentCallSourceTypeEnum } from '@/api'
import { describe, expect, it } from 'vitest'
import {
  formatDuration,
  outcomeLabels,
  outcomeTagTheme,
  sourceLabels,
  sourceTagTheme,
} from '../recent-call'

describe('recent-call utils', () => {
  it('maps outcomes to Chinese labels', () => {
    expect(outcomeLabels[McpRecentCallOutcomeEnum.Success]).toBe('成功')
    expect(outcomeLabels[McpRecentCallOutcomeEnum.ToolError]).toBe('工具错误')
    expect(outcomeLabels[McpRecentCallOutcomeEnum.ProtocolError]).toBe('协议错误')
    expect(outcomeLabels[McpRecentCallOutcomeEnum.InternalError]).toBe('内部错误')
    expect(outcomeLabels[McpRecentCallOutcomeEnum.Cancelled]).toBe('已取消')
  })

  it('assigns tag themes for outcomes', () => {
    expect(outcomeTagTheme[McpRecentCallOutcomeEnum.Success]).toBe('default')
    expect(outcomeTagTheme[McpRecentCallOutcomeEnum.ToolError]).toBe('danger')
    expect(outcomeTagTheme[McpRecentCallOutcomeEnum.Cancelled]).toBe('secondary')
  })

  it('maps sources to Chinese labels', () => {
    expect(sourceLabels[McpRecentCallSourceTypeEnum.BuiltIn]).toBe('内置')
    expect(sourceLabels[McpRecentCallSourceTypeEnum.Plugin]).toBe('插件')
    expect(sourceLabels[McpRecentCallSourceTypeEnum.Unknown]).toBe('未知')
  })

  it('assigns tag themes for sources', () => {
    expect(sourceTagTheme[McpRecentCallSourceTypeEnum.BuiltIn]).toBe('default')
    expect(sourceTagTheme[McpRecentCallSourceTypeEnum.Plugin]).toBe('primary')
    expect(sourceTagTheme[McpRecentCallSourceTypeEnum.Unknown]).toBe('secondary')
  })

  it('formats duration in milliseconds or seconds', () => {
    expect(formatDuration(0)).toBe('0 ms')
    expect(formatDuration(999)).toBe('999 ms')
    expect(formatDuration(1500)).toBe('1.50 s')
  })
})
