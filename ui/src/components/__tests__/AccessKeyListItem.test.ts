import type { McpAccessKey } from '@/api'
import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AccessKeyListItem from '../AccessKeyListItem.vue'

type UpdateAccessKeyCall = (request: {
  name: string
  updateMcpAccessKeyRequest: {
    displayName: string
    allowedIpRanges: string[]
    allowedTools: string[]
    expiresAt?: string
    enabled: boolean
  }
}) => Promise<{ data: Record<string, never> }>

const { dateIsBefore, updateMcpAccessKey, toastSuccess } = vi.hoisted(() => ({
  dateIsBefore: vi.fn<() => boolean>(),
  updateMcpAccessKey: vi.fn<UpdateAccessKeyCall>(),
  toastSuccess: vi.fn<(message: string) => void>(),
}))

vi.mock('@/api', () => ({
  mcpConsoleApiClient: {
    updateMcpAccessKey,
  },
}))

vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    date: {
      dayjs: () => ({ isBefore: dateIsBefore }),
      format: (value: string) => value,
      timeAgo: (value: string) => value,
    },
  },
}))

vi.mock('@halo-dev/components', async () => {
  const components =
    await vi.importActual<typeof import('@halo-dev/components')>('@halo-dev/components')
  return {
    ...components,
    Toast: {
      ...components.Toast,
      success: toastSuccess,
    },
  }
})

const accessKey: McpAccessKey = {
  name: 'key-1',
  displayName: '内容自动化',
  keyPrefix: 'hmcp_test',
  ownerName: 'admin',
  enabled: true,
  expiresAt: '2026-12-01T00:00:00Z',
  allowedIpRanges: ['203.0.113.0/24'],
  allowedTools: ['halo_get_post'],
}

function mountListItem(mcpAccessKey = accessKey) {
  const queryClient = new QueryClient()
  return mount(AccessKeyListItem, {
    props: { mcpAccessKey },
    global: {
      plugins: [[VueQueryPlugin, { queryClient }]],
      stubs: {
        RouterLink: true,
      },
    },
  })
}

describe('AccessKeyListItem', () => {
  beforeEach(() => {
    dateIsBefore.mockReset().mockReturnValue(false)
    updateMcpAccessKey.mockReset().mockResolvedValue({ data: {} })
    toastSuccess.mockReset()
  })

  it('updates the complete access key when disabling it', async () => {
    const wrapper = mountListItem()

    await wrapper.get('button[role="switch"]').trigger('click')
    await flushPromises()

    expect(updateMcpAccessKey).toHaveBeenCalledWith({
      name: 'key-1',
      updateMcpAccessKeyRequest: {
        displayName: '内容自动化',
        allowedIpRanges: ['203.0.113.0/24'],
        allowedTools: ['halo_get_post'],
        expiresAt: '2026-12-01T00:00:00Z',
        enabled: false,
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('MCP 密钥已禁用')
  })

  it('shows whether IP access is restricted', () => {
    expect(mountListItem().text()).toContain('限制 1 个 IP 范围')
    expect(mountListItem({ ...accessKey, allowedIpRanges: [] }).text()).toContain('IP 不限制')
  })

  it('shows automatic access instead of a tool count for the wildcard', () => {
    const wrapper = mountListItem({ ...accessKey, allowedTools: ['*'] })

    expect(wrapper.text()).toContain('全部工具（自动）')
    expect(wrapper.text()).not.toContain('1 个工具')
  })

  it('uses the expiration text to indicate an expired access key', () => {
    dateIsBefore.mockReturnValue(true)

    const wrapper = mountListItem()

    expect(wrapper.text()).toContain('已过期：2026-12-01T00:00:00Z')
    expect(wrapper.text()).not.toContain('删除中')
  })
})
