import { McpRecentCallOutcomeEnum, McpRecentCallSourceTypeEnum } from '@/api'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref, type Ref } from 'vue'
import McpRecentCalls from '../McpRecentCalls.vue'

const { listMcpRecentCalls, refreshMock } = vi.hoisted(() => ({
  listMcpRecentCalls: vi.fn<() => unknown>(),
  refreshMock: vi.fn<() => void>(),
}))

vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    date: {
      dayjs: () => ({}),
      format: (value: string) => value,
      timeAgo: (value: string) => value,
    },
  },
}))

vi.mock('@/api', () => ({
  mcpConsoleApiClient: {
    listMcpRecentCalls,
  },
  McpRecentCallOutcomeEnum: {
    Success: 'SUCCESS',
    ToolError: 'TOOL_ERROR',
    ProtocolError: 'PROTOCOL_ERROR',
    InternalError: 'INTERNAL_ERROR',
    Cancelled: 'CANCELLED',
  },
  McpRecentCallSourceTypeEnum: {
    BuiltIn: 'BUILT_IN',
    Plugin: 'PLUGIN',
    Unknown: 'UNKNOWN',
  },
}))

vi.mock('@/composables/useAccessKeys', () => ({
  useAccessKeys: vi.fn<() => unknown>(() => ({
    data: ref([
      {
        name: 'key-1',
        displayName: '内容自动化',
        keyPrefix: 'hmcp_test',
        ownerName: 'admin',
        enabled: true,
        allowedTools: [],
      },
    ]),
  })),
}))

vi.mock('@/composables/useTools', () => ({
  useTools: vi.fn<() => unknown>(() => ({
    data: ref([
      {
        name: 'halo_get_post',
        title: '获取文章',
        description: '',
        readOnly: true,
        destructive: false,
        category: 'POST',
        source: { type: 'BUILT_IN', pluginName: 'plugin-mcp-server', displayName: 'MCP Server' },
      },
    ]),
  })),
}))

interface RecentCallQuery {
  page: number
  size: number
  keyId?: string
  toolName?: string
  outcome?: string
}

vi.mock('@/composables/useRecentCalls', () => ({
  useRecentCalls: vi.fn<(query: Ref<RecentCallQuery>) => unknown>((query) => {
    return {
      data: ref({
        items: [
          {
            id: 1,
            startedAt: '2026-08-22T08:00:00Z',
            durationMillis: 150,
            keyId: 'key-1',
            keyDisplayName: '内容自动化',
            keyPrefix: 'hmcp_test',
            ownerName: 'admin',
            toolName: 'halo_get_post',
            sourceType: McpRecentCallSourceTypeEnum.BuiltIn,
            outcome: McpRecentCallOutcomeEnum.Success,
          },
        ],
        page: query.value.page,
        size: 20,
        total: 1,
        totalPages: 1,
        hasNext: false,
      }),
      isLoading: ref(false),
      isError: ref(false),
      error: ref(null),
      isFetching: ref(false),
      refetch: refreshMock,
    }
  }),
}))

const HaloStub = defineComponent({
  props: ['title', 'type', 'closable', 'body-class'],
  template: '<div><slot /><slot name="actions" /><slot name="footer" /></div>',
})

function mountComponent() {
  const buttonStub = defineComponent({
    props: ['loading', 'disabled', 'size'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')"><slot /></button>',
  })
  const entityStub = defineComponent({
    template: '<div class="v-entity"><slot name="start" /><slot name="end" /></div>',
  })
  const entityFieldStub = defineComponent({
    props: ['title', 'description'],
    template:
      '<div><div class="title">{{ title }}</div><div class="description"><slot name="description">{{ description }}</slot></div></div>',
  })
  const paginationStub = defineComponent({
    props: ['page', 'size', 'total', 'sizeOptions'],
    emits: ['update:page'],
    template: '<div><button @click="$emit(\'update:page\', 2)">Next</button></div>',
  })
  const tagStub = defineComponent({
    props: ['theme'],
    template: '<span class="v-tag"><slot /></span>',
  })
  const filterDropdownStub = defineComponent({
    props: ['modelValue', 'label', 'items'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="item in items" :key="item.value" :value="item.value">{{ item.label }}</option></select>',
  })

  return mount(McpRecentCalls, {
    global: {
      stubs: {
        Alert: HaloStub,
        VAlert: HaloStub,
        Button: buttonStub,
        VButton: buttonStub,
        Card: HaloStub,
        VCard: HaloStub,
        Empty: HaloStub,
        VEmpty: HaloStub,
        Entity: entityStub,
        VEntity: entityStub,
        EntityContainer: defineComponent({ template: '<div><slot /></div>' }),
        VEntityContainer: defineComponent({ template: '<div><slot /></div>' }),
        EntityField: entityFieldStub,
        VEntityField: entityFieldStub,
        Loading: defineComponent({ template: '<div />' }),
        VLoading: defineComponent({ template: '<div />' }),
        Pagination: paginationStub,
        VPagination: paginationStub,
        Tag: tagStub,
        VTag: tagStub,
        FilterDropdown: filterDropdownStub,
      },
      directives: {
        tooltip: () => {},
      },
    },
  })
}

describe('McpRecentCalls', () => {
  beforeEach(() => {
    listMcpRecentCalls.mockReset()
    refreshMock.mockReset()
  })

  it('renders Chinese labels for source and outcome', () => {
    const wrapper = mountComponent()

    expect(wrapper.text()).toContain('内置')
    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('获取文章')
    expect(wrapper.text()).toContain('halo_get_post')
    expect(wrapper.text()).toContain('内容自动化')
  })

  it('refreshes when clicking the refresh button', async () => {
    const wrapper = mountComponent()
    const buttons = wrapper.findAll('button')
    const refreshButton = buttons.find((b) => b.text().includes('刷新'))
    expect(refreshButton).toBeDefined()

    await refreshButton!.trigger('click')

    expect(refreshMock).toHaveBeenCalled()
  })

  it('resets page to 1 when a filter changes', async () => {
    const wrapper = mountComponent()
    const { useRecentCalls } = await import('@/composables/useRecentCalls')
    const mockedUseRecentCalls = vi.mocked(useRecentCalls)

    // Move to page 2 first.
    const buttons = wrapper.findAll('button')
    const nextButton = buttons.find((b) => b.text().includes('Next'))
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    const calls = mockedUseRecentCalls.mock.calls
    const lastQueryBeforeFilter = calls[calls.length - 1]![0]
    expect(lastQueryBeforeFilter.value.page).toBe(2)

    // Change outcome filter.
    const selects = wrapper.findAll('select')
    const outcomeSelect = selects[2]!
    outcomeSelect.setValue('SUCCESS')
    await nextTick()

    const callsAfterFilter = mockedUseRecentCalls.mock.calls
    const lastQueryAfterFilter = callsAfterFilter[callsAfterFilter.length - 1]![0]
    expect(lastQueryAfterFilter.value.page).toBe(1)
    expect(lastQueryAfterFilter.value.outcome).toBe('SUCCESS')
  })
})
