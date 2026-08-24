import type { McpAccessKey } from '@/api'
import { describe, expect, it } from 'vitest'
import { accessKeyRefetchInterval } from '../useAccessKeys'

function accessKey(deletionTimestamp?: string): McpAccessKey {
  return {
    name: 'test-key',
    displayName: 'Test key',
    keyPrefix: 'hmcp_test',
    ownerName: 'admin',
    enabled: true,
    allowedIpRanges: [],
    allowedTools: [],
    deletionTimestamp,
  }
}

describe('accessKeyRefetchInterval', () => {
  it('polls while a key is being deleted', () => {
    expect(accessKeyRefetchInterval([accessKey('2026-08-21T08:00:00Z')])).toBe(1000)
  })

  it('stops polling when no key is being deleted', () => {
    expect(accessKeyRefetchInterval([accessKey()])).toBe(false)
    expect(accessKeyRefetchInterval()).toBe(false)
  })
})
