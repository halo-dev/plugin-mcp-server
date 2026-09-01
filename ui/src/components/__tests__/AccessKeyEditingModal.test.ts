import type { McpAccessKey } from '@/api'
import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import AccessKeyEditingModal from '../AccessKeyEditingModal.vue'

const { refetch } = vi.hoisted(() => ({ refetch: vi.fn<() => void>() }))

vi.mock('@/composables/useTools', () => ({
  useTools: () => ({
    data: ref(undefined),
    isLoading: ref(false),
    isError: ref(true),
    isFetching: ref(false),
    refetch,
  }),
}))

vi.mock('@/api', () => ({
  mcpConsoleApiClient: { updateMcpAccessKey: vi.fn<() => void>() },
}))

vi.mock('@/components/AccessKeyForm.vue', () => ({
  default: { template: '<div data-testid="access-key-form" />' },
}))

vi.mock('@halo-dev/components', () => ({
  Toast: { success: () => undefined },
  VAlert: { template: '<div><slot /><slot name="actions" /></div>' },
  VButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  VLoading: { template: '<div />' },
  VModal: { template: '<div><slot /><slot name="footer" /></div>' },
  VSpace: { template: '<div><slot /></div>' },
}))

const accessKey: McpAccessKey = {
  name: 'key-1',
  displayName: 'Automation',
  keyPrefix: 'hmcp_test',
  ownerName: 'admin',
  enabled: true,
  allowedIpRanges: [],
  allowedTools: ['halo_get_post'],
}

describe('AccessKeyEditingModal', () => {
  it('does not mount the form when the tool catalog failed to load', async () => {
    const wrapper = mount(AccessKeyEditingModal, {
      props: { accessKey },
      global: {
        plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]],
      },
    })

    expect(wrapper.find('[data-testid="access-key-form"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('重试')
    await wrapper.get('button').trigger('click')
    expect(refetch).toHaveBeenCalled()
  })
})
