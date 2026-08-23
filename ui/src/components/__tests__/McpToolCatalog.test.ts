import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import McpToolCatalog from '../McpToolCatalog.vue'

vi.mock('@/composables/useTools', () => ({
  useTools: () => ({
    data: ref([
      {
        available: true,
        category: 'POST',
        description: '分页查询文章。',
        destructive: false,
        inputSchema: { type: 'object', properties: { page: { type: 'integer' } } },
        name: 'halo_list_posts',
        outputSchema: { type: 'object', properties: { items: { type: 'array' } } },
        readOnly: true,
        source: {
          type: 'BUILT_IN',
          pluginName: 'plugin-mcp-server',
          displayName: 'MCP Server',
        },
        title: '查询文章',
      },
    ]),
  }),
}))

const ContainerStub = defineComponent({
  template: '<div><slot /></div>',
})

const DetailsStub = defineComponent({
  props: ['tool'],
  emits: ['close'],
  template: '<div data-testid="details">{{ tool.name }}</div>',
})

describe('McpToolCatalog', () => {
  it('opens tool details when a card is activated', async () => {
    const wrapper = mount(McpToolCatalog, {
      global: {
        stubs: {
          VCard: ContainerStub,
          VTag: ContainerStub,
          McpToolDetailsModal: DetailsStub,
        },
      },
    })

    expect(wrapper.find('[data-testid="details"]').exists()).toBe(false)

    await wrapper.get('button[aria-label="查看 查询文章 的详情"]').trigger('click')

    expect(wrapper.get('[data-testid="details"]').text()).toBe('halo_list_posts')
  })
})
