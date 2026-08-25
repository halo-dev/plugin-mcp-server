import { describe, expect, it } from 'vitest'

import { mcpClientGuides } from '../mcp-config'

describe('mcpClientGuides', () => {
  it('embeds no bearer material in any guide content or install URL', () => {
    for (const guide of mcpClientGuides()) {
      expect(guide.content).not.toContain('hmcp_')
      expect(guide.content).not.toMatch(/Bearer (?!\$)/)
      const installPayload = decodeURIComponent(guide.installUrl ?? '')
      expect(installPayload).not.toContain('hmcp_')
      expect(installPayload).not.toMatch(/Bearer (?!\$)/)
    }
  })

  it('references the token from the environment for CLI and file-based clients', () => {
    const guides = mcpClientGuides()

    const claudeCode = guides.find((guide) => guide.id === 'claude-code')
    expect(claudeCode?.content).toContain("--header 'Authorization: Bearer ${HALO_MCP_TOKEN}'")

    const codex = guides.find((guide) => guide.id === 'codex')
    expect(codex?.content).toContain('bearer_token_env_var = "HALO_MCP_TOKEN"')
    expect(codex?.content).not.toContain('http_headers')

    const cursor = guides.find((guide) => guide.id === 'cursor')
    expect(cursor?.content).toContain('Bearer ${env:HALO_MCP_TOKEN}')
  })

  it('keeps only endpoint metadata and an env reference in the Cursor install URL', () => {
    const cursor = mcpClientGuides().find((guide) => guide.id === 'cursor')

    const config = JSON.parse(atob(cursor!.installUrl!.split('config=')[1]!))
    expect(config.type).toBe('http')
    expect(config.url).toMatch(/\/mcp$/)
    expect(config.headers.Authorization).toBe('Bearer ${env:HALO_MCP_TOKEN}')
  })

  it('lets VS Code prompt for the token through secure input storage', () => {
    const vscode = mcpClientGuides().find((guide) => guide.id === 'vscode')

    expect(vscode?.content).toContain('${input:halo-mcp-token}')
    expect(vscode?.content).toContain('"password": true')

    const payload = JSON.parse(
      decodeURIComponent(vscode!.installUrl!.slice('vscode:mcp/install?'.length)),
    )
    expect(payload.headers.Authorization).toBe('Bearer ${input:halo-mcp-token}')
    expect(payload.inputs).toHaveLength(1)
    expect(payload.inputs[0]).toMatchObject({ type: 'promptString', password: true })
  })
})
