import { describe, expect, it } from 'vitest'

import { mcpClientGuides } from '../mcp-config'

describe('mcpClientGuides', () => {
  it('writes the Codex token directly to a static authorization header', () => {
    const codex = mcpClientGuides('hmcp_secret').find((guide) => guide.id === 'codex')

    expect(codex?.content).toContain('http_headers = { Authorization = "Bearer hmcp_secret" }')
    expect(codex?.content).not.toContain('bearer_token_env_var')
  })
})
