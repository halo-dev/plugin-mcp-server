import type { McpTool } from '@/api'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import McpToolCard from '../McpToolCard.vue'

const tool: McpTool = {
  available: true,
  category: 'POST',
  description: '分页查询文章。',
  destructive: false,
  inputSchema: { type: 'object' },
  name: 'halo_list_posts',
  outputSchema: { type: 'object' },
  readOnly: true,
  source: {
    type: 'BUILT_IN',
    pluginName: 'plugin-mcp-server',
    displayName: 'MCP Server',
  },
  title: '查询文章',
}

const TagStub = defineComponent({
  template: '<span><slot /></span>',
})

describe('McpToolCard', () => {
  it('emits activate from the catalog variant', async () => {
    const wrapper = mount(McpToolCard, {
      props: { tool },
      global: { stubs: { VTag: TagStub } },
    })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('activate')).toHaveLength(1)
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('renders a checkbox and selected border in the selectable variant', async () => {
    const wrapper = mount(McpToolCard, {
      props: { tool, selectable: true, modelValue: false },
      global: { stubs: { VTag: TagStub } },
    })

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.setProps({ modelValue: true })

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    expect(wrapper.get('label').classes()).toContain('border-[rgb(var(--colors-primary))]')
  })
})
