import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import McpToolDetailsModal from '../McpToolDetailsModal.vue'

const ContainerStub = defineComponent({
  props: ['title'],
  template: '<div><h1>{{ title }}</h1><slot /><slot name="footer" /></div>',
})

function mountModal(outputSchema?: Record<string, unknown>) {
  return mount(McpToolDetailsModal, {
    props: {
      tool: {
        available: true,
        category: 'POST',
        description: '分页查询文章。',
        destructive: false,
        inputSchema: { type: 'object', properties: { page: { type: 'integer' } } },
        name: 'halo_list_posts',
        outputSchema,
        readOnly: true,
        source: {
          type: 'BUILT_IN',
          pluginName: 'plugin-mcp-server',
          displayName: 'MCP Server',
          version: '1.0.0',
        },
        title: '查询文章',
      },
    },
    global: {
      stubs: {
        Modal: ContainerStub,
        VModal: ContainerStub,
        Tag: ContainerStub,
        VTag: ContainerStub,
        Space: ContainerStub,
        VSpace: ContainerStub,
        Button: ContainerStub,
        VButton: ContainerStub,
      },
    },
  })
}

describe('McpToolDetailsModal', () => {
  it('renders diagnostic metadata and input/output schemas', () => {
    const wrapper = mountModal({ type: 'object', properties: { items: { type: 'array' } } })

    expect(wrapper.text()).toContain('查询文章')
    expect(wrapper.text()).toContain('halo_list_posts')
    expect(wrapper.text()).toContain('分页查询文章。')
    expect(wrapper.text()).toContain('MCP Server')
    expect(wrapper.text()).toContain('v1.0.0')
    expect(wrapper.text()).toContain('Input Schema')
    expect(wrapper.text()).toContain('"page":')
    expect(wrapper.text()).toContain('Output Schema')
    expect(wrapper.text()).toContain('"items":')
  })

  it('states when an output schema is not declared', () => {
    const wrapper = mountModal()

    expect(wrapper.text()).toContain('该工具未声明输出 Schema')
  })
})
