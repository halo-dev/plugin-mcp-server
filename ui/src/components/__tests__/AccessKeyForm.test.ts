import type { McpAccessKey } from '@/api'
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
  },
  emits: ['submit'],
  template: `
    <form v-if="type === 'form'" @submit.prevent="$emit('submit')"><slot :index="0" /></form>
    <div v-else><slot :index="0" /></div>
  `,
})

function mountForm(accessKey?: McpAccessKey) {
  return mount(AccessKeyForm, {
    props: { accessKey, tools: [] },
    global: {
      stubs: {
        FormKit: FormKitStub,
        McpToolCard: true,
        VButton: true,
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
})
