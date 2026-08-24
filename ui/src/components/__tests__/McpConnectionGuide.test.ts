import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import McpConnectionGuide from '../McpConnectionGuide.vue'

const SECRET_MARKER = 'hmcp_component_secret_marker'

const { clipboardWrite } = vi.hoisted(() => ({
  clipboardWrite: vi.fn<(value: string) => Promise<void>>(),
}))

vi.mock('@halo-dev/components', () => ({
  Toast: {
    success: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
  },
}))

function tab(wrapper: VueWrapper, label: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(label))
  if (!button) {
    throw new Error(`Missing ${label} tab`)
  }
  return button
}

describe('McpConnectionGuide', () => {
  beforeEach(() => {
    clipboardWrite.mockReset().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: clipboardWrite },
    })
  })

  it('keeps a legacy token attribute out of rendered, copied and navigated artifacts', async () => {
    const wrapper = mount(McpConnectionGuide, {
      attrs: { token: SECRET_MARKER },
    })

    expect(wrapper.html()).not.toContain(SECRET_MARKER)
    expect(wrapper.text()).toContain('“添加端点”不包含认证信息')

    await tab(wrapper, 'Cursor').trigger('click')
    expect(wrapper.get('a').text()).toBe('添加端点')
    expect(wrapper.get('a').attributes('href')).not.toContain(SECRET_MARKER)

    await tab(wrapper, 'VS Code').trigger('click')
    expect(wrapper.get('a').attributes('href')).not.toContain(SECRET_MARKER)

    const copyButton = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().trim() === '复制')
    if (!copyButton) {
      throw new Error('Missing copy button')
    }
    await copyButton.trigger('click')
    await flushPromises()

    expect(clipboardWrite).toHaveBeenCalledOnce()
    expect(clipboardWrite.mock.calls[0]?.[0]).not.toContain(SECRET_MARKER)
  })
})
