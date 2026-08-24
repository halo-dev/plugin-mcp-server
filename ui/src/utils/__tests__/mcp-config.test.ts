import { describe, expect, it } from 'vitest'

import { mcpClientGuides, mcpEndpoint, mcpHttpConfig, type McpClientGuide } from '../mcp-config'

const SECRET_MARKER = 'hmcp_regression_secret_marker'

function guidesFromLegacySecret(secret: string) {
  const legacyGenerator = mcpClientGuides as unknown as (secret: string) => McpClientGuide[]
  return legacyGenerator(secret)
}

describe('mcpClientGuides', () => {
  it('does not expose a supplied token in generated artifacts', () => {
    const guides = guidesFromLegacySecret(SECRET_MARKER)
    const legacyHttpGenerator = mcpHttpConfig as unknown as (secret: string) => string

    for (const guide of guides) {
      const artifacts = [guide.content, guide.installUrl ?? '']
      expect(artifacts.join('\n')).not.toContain(SECRET_MARKER)
      expect(artifacts.join('\n')).not.toContain(encodeURIComponent(SECRET_MARKER))
    }
    expect(legacyHttpGenerator(SECRET_MARKER)).not.toContain(SECRET_MARKER)

    const cursor = guides.find((guide) => guide.id === 'cursor')
    const cursorUrl = new URL(cursor!.installUrl!)
    const cursorConfig = JSON.parse(window.atob(cursorUrl.searchParams.get('config')!))
    expect(cursorConfig).toEqual({ type: 'http', url: mcpEndpoint() })

    const vscode = guides.find((guide) => guide.id === 'vscode')
    const encodedVscodeConfig = vscode!.installUrl!.split('?')[1]
    expect(encodedVscodeConfig).toBeDefined()
    const vscodeConfig = JSON.parse(decodeURIComponent(encodedVscodeConfig!))
    expect(vscodeConfig).toEqual({ name: 'halo', type: 'http', url: mcpEndpoint() })
  })

  it('keeps working authentication setup through client-native secret indirection', () => {
    const guides = mcpClientGuides()

    expect(guides).toHaveLength(4)
    expect(guides.every((guide) => guide.content.includes(mcpEndpoint()))).toBe(true)
    const claude = guides.find((guide) => guide.id === 'claude-code')
    expect(claude?.content).toContain('"Authorization": "Bearer ${HALO_MCP_TOKEN}"')
    expect(() => JSON.parse(claude!.content)).not.toThrow()
    expect(guides.find((guide) => guide.id === 'codex')?.content).toContain(
      'bearer_token_env_var = "HALO_MCP_TOKEN"',
    )
    const cursor = guides.find((guide) => guide.id === 'cursor')
    expect(cursor?.content).toContain('"Authorization": "Bearer ${env:HALO_MCP_TOKEN}"')
    expect(() => JSON.parse(cursor!.content)).not.toThrow()

    const vscode = guides.find((guide) => guide.id === 'vscode')
    expect(vscode?.content).toContain('"Authorization": "Bearer ${input:halo-mcp-token}"')
    expect(vscode?.content).toContain('"password": true')
    expect(() => JSON.parse(vscode!.content)).not.toThrow()
  })
})
