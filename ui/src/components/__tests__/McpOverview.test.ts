import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref, type Ref } from 'vue'
import McpOverview from '../McpOverview.vue'

const accessKeysData = ref<unknown[] | undefined>(undefined)
const toolsData = ref<unknown[] | undefined>(undefined)
const recentCallsData = ref<{ total: number } | undefined>(undefined)

vi.mock('@/composables/useAccessKeys', () => ({
  useAccessKeys: () => ({ data: accessKeysData }),
}))

vi.mock('@/composables/useTools', () => ({
  useTools: () => ({ data: toolsData }),
}))

interface RecentCallQuery {
  page: number
  size: number
  outcome?: string
}

vi.mock('@/composables/useRecentCalls', () => ({
  useRecentCalls: (query: Ref<RecentCallQuery>) => ({
    data: ref(
      query.value.outcome
        ? recentCallsData.value && { total: Math.floor(recentCallsData.value.total / 2) }
        : recentCallsData.value,
    ),
  }),
}))

vi.mock('@/api', () => ({
  McpRecentCallOutcomeEnum: {
    Success: 'SUCCESS',
  },
}))

const stub = defineComponent({
  props: ['title', 'type', 'closable'],
  template: '<div><slot /><slot name="description" /></div>',
})

function mountComponent() {
  return mount(McpOverview, {
    global: {
      stubs: {
        VCard: stub,
        VAlert: stub,
      },
    },
  })
}

describe('McpOverview', () => {
  it('renders key, tool and call stats', () => {
    accessKeysData.value = [
      { enabled: true },
      { enabled: false },
      { enabled: true, deletionTimestamp: '2026-08-22T08:00:00Z' },
    ]
    toolsData.value = [
      { readOnly: true, destructive: false },
      { readOnly: false, destructive: false },
      { readOnly: false, destructive: true },
    ]
    recentCallsData.value = { total: 10 }

    const wrapper = mountComponent()
    const text = wrapper.text()

    expect(text).toContain('3')
    expect(text).toContain('1 个已启用')
    expect(text).toContain('只读 1 · 写入 1 · 破坏性 1')
    expect(text).toContain('成功 5 次')
    expect(text).toContain('50%')
    expect(text).toContain('共 10 次调用')
  })

  it('shows onboarding hint when there are no keys', () => {
    accessKeysData.value = []

    const wrapper = mountComponent()

    expect(wrapper.text()).toContain('尚未创建 MCP 访问密钥')
  })

  it('hides success rate when there are no calls', () => {
    accessKeysData.value = [{ enabled: true }]
    toolsData.value = []
    recentCallsData.value = { total: 0 }

    const wrapper = mountComponent()

    expect(wrapper.text()).toContain('暂无调用记录')
    expect(wrapper.text()).not.toContain('0%')
  })
})
