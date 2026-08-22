import type { McpRecentCallPage } from '@/api'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref, type Ref } from 'vue'
import { QK_RECENT_CALLS, useRecentCalls, type RecentCallQuery } from '../useRecentCalls'

const { listMcpRecentCalls, useQueryMock, visibility } = vi.hoisted(() => ({
  listMcpRecentCalls: vi.fn<() => Promise<{ data: McpRecentCallPage }>>(),
  useQueryMock: vi.fn<(options: Record<string, unknown>) => { data: Ref<null> }>(),
  visibility: vi.fn<() => Ref<'visible' | 'hidden'>>(),
}))

vi.mock('@tanstack/vue-query', async () => {
  const actual = await vi.importActual<typeof import('@tanstack/vue-query')>('@tanstack/vue-query')
  return {
    ...actual,
    useQuery: useQueryMock,
  }
})

vi.mock('@vueuse/core', () => ({
  useDocumentVisibility: visibility,
}))

vi.mock('@/api', () => ({
  mcpConsoleApiClient: {
    listMcpRecentCalls,
  },
}))

function mountComposable(query: RecentCallQuery) {
  const queryRef = ref(query)

  const TestComponent = defineComponent({
    setup() {
      const result = useRecentCalls(queryRef)
      return { result, query: queryRef }
    },
    template: '<div />',
  })

  return mount(TestComponent)
}

// Minimal mount helper to avoid importing @vue/test-utils at the top level before mocks.
async function mount(component: ReturnType<typeof defineComponent>) {
  const { mount: mountImpl } = await import('@vue/test-utils')
  return mountImpl(component)
}

describe('useRecentCalls', () => {
  it('calls the API with the provided query', async () => {
    visibility.mockReturnValue(ref('visible'))
    const page: McpRecentCallPage = {
      items: [],
      page: 2,
      size: 20,
      total: 0,
      totalPages: 0,
      hasNext: false,
    }
    listMcpRecentCalls.mockResolvedValue({ data: page })

    await mountComposable({
      page: 2,
      size: 20,
      keyId: 'key-1',
      toolName: 'halo_get_post',
      outcome: 'SUCCESS',
    })

    const calls = useQueryMock.mock.calls
    const options = calls[calls.length - 1]![0]
    expect((options.queryKey as Ref<unknown>).value).toEqual([QK_RECENT_CALLS, expect.any(Object)])

    const result = await (options.queryFn as () => Promise<McpRecentCallPage>)()
    expect(listMcpRecentCalls).toHaveBeenCalledWith({
      page: 2,
      size: 20,
      keyId: 'key-1',
      toolName: 'halo_get_post',
      outcome: 'SUCCESS',
    })
    expect(result).toEqual(page)
  })

  it('refetches every 5 seconds while visible', async () => {
    visibility.mockReturnValue(ref('visible'))
    useQueryMock.mockReturnValue({ data: ref(null) })

    await mountComposable({ page: 1, size: 20 })

    const calls = useQueryMock.mock.calls
    const options = calls[calls.length - 1]![0]
    expect((options.refetchInterval as () => number | false)()).toBe(5000)
    expect(options.keepPreviousData).toBe(true)
  })

  it('stops refetching while hidden', async () => {
    visibility.mockReturnValue(ref('hidden'))
    useQueryMock.mockReturnValue({ data: ref(null) })

    await mountComposable({ page: 1, size: 20 })

    const calls = useQueryMock.mock.calls
    const options = calls[calls.length - 1]![0]
    expect((options.refetchInterval as () => number | false)()).toBe(false)
  })
})
