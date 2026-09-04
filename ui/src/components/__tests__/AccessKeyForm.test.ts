import type { McpAccessKey, McpTool } from '@/api'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import AccessKeyForm from '../AccessKeyForm.vue'

vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    date: {
      toDatetimeLocal: (value: string) => value,
      toISOString: (value: string) => value,
    },
  },
}))

const FormKitStub = defineComponent({
  name: 'FormKit',
  props: {
    type: String,
    name: String,
    modelValue: [String, Boolean],
  },
  emits: ['submit', 'update:modelValue'],
  template: `
    <form v-if="type === 'form'" @submit.prevent="$emit('submit')"><slot :index="0" /></form>
    <div v-else><slot :index="0" /></div>
  `,
})

function mountForm(accessKey?: McpAccessKey, tools: McpTool[] = []) {
  return mount(AccessKeyForm, {
    props: { accessKey, tools },
    global: {
      stubs: {
        FormKit: FormKitStub,
        McpToolCard: true,
        VButton: true,
        VAlert: true,
        VSpace: true,
        VTag: true,
      },
    },
  })
}

describe('AccessKeyForm', () => {
  it('submits an empty IP allowlist for an unrestricted new key', async () => {
    const wrapper = mountForm()

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
      allowedIpRanges: [],
      allowedTools: [],
    })
  })

  it('hides tool selection and submits the wildcard when all tools are automatic', async () => {
    const wrapper = mountForm(undefined, [
      {
        name: 'halo_get_post',
        category: 'POST',
        readOnly: true,
        destructive: false,
        available: true,
        inputSchema: {},
        source: {
          type: 'BUILT_IN',
          pluginName: 'mcp-server',
          displayName: 'MCP Server',
        },
      },
    ])
    const switchInput = wrapper
      .findAllComponents(FormKitStub)
      .find((input) => input.props('name') === 'allowAllTools')

    switchInput?.vm.$emit('update:modelValue', true)
    await wrapper.vm.$nextTick()
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('未来新增的全部工具权限')
    expect(wrapper.findComponent({ name: 'McpToolCard' }).exists()).toBe(false)
    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({ allowedTools: ['*'] })
  })

  it('normalizes each line from the IP allowlist textarea before submitting', async () => {
    const wrapper = mountForm({
      name: 'key-1',
      displayName: 'Automation',
      keyPrefix: 'hmcp_test',
      ownerName: 'admin',
      enabled: true,
      allowedIpRanges: [' 203.0.113.10 ', '203.0.113.10', '2001:db8::/32'],
      allowedTools: [],
    })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
      allowedIpRanges: ['203.0.113.10', '2001:db8::/32'],
    })
  })

  it('preserves existing permissions for tools missing from the current catalog', async () => {
    const wrapper = mountForm({
      name: 'key-1',
      displayName: 'Automation',
      keyPrefix: 'hmcp_test',
      ownerName: 'admin',
      enabled: true,
      allowedIpRanges: [],
      allowedTools: ['unavailable__tool'],
    })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({
      allowedTools: ['unavailable__tool'],
    })
    expect(wrapper.text()).toContain('暂不可用的既有工具授权默认保留')
  })

  it('clears permissions for tools missing from the current catalog', async () => {
    const wrapper = mountForm({
      name: 'key-1',
      displayName: 'Automation',
      keyPrefix: 'hmcp_test',
      ownerName: 'admin',
      enabled: true,
      allowedIpRanges: [],
      allowedTools: ['unavailable__tool'],
    })

    const clear = wrapper.findAll('button').find((button) => button.text() === '清空')
    await clear?.trigger('click')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({ allowedTools: [] })
  })
})
