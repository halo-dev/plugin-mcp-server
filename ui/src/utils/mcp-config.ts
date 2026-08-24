export function mcpEndpoint() {
  return `${window.location.origin}/mcp`
}

const SERVER_NAME = 'halo'
const CLAUDE_TOKEN_REFERENCE = '${HALO_MCP_TOKEN}'
const CURSOR_TOKEN_REFERENCE = '${env:HALO_MCP_TOKEN}'
const VSCODE_TOKEN_INPUT_ID = 'halo-mcp-token'

export function mcpHttpConfig() {
  return JSON.stringify(
    {
      mcpServers: {
        [SERVER_NAME]: {
          type: 'http',
          url: mcpEndpoint(),
          headers: {
            Authorization: `Bearer ${CURSOR_TOKEN_REFERENCE}`,
          },
        },
      },
    },
    null,
    2,
  )
}

export type McpClientId = 'claude-code' | 'codex' | 'cursor' | 'vscode'

export interface McpClientGuide {
  id: McpClientId
  label: string
  content: string
  installUrl?: string
}

export function mcpClientGuides(): McpClientGuide[] {
  const endpoint = mcpEndpoint()
  const installConfig = {
    type: 'http',
    url: endpoint,
  }

  const guides: McpClientGuide[] = [
    {
      id: 'claude-code',
      label: 'Claude Code',
      content: JSON.stringify(
        {
          mcpServers: {
            [SERVER_NAME]: {
              type: 'http',
              url: endpoint,
              headers: { Authorization: `Bearer ${CLAUDE_TOKEN_REFERENCE}` },
            },
          },
        },
        null,
        2,
      ),
    },
    {
      id: 'codex',
      label: 'Codex',
      content: `# ~/.codex/config.toml
[mcp_servers.${SERVER_NAME}]
url = "${endpoint}"
bearer_token_env_var = "HALO_MCP_TOKEN"`,
    },
    {
      id: 'cursor',
      label: 'Cursor',
      content: mcpHttpConfig(),
      installUrl: `cursor://anysphere.cursor-deeplink/mcp/install?name=${SERVER_NAME}&config=${window.btoa(
        JSON.stringify(installConfig),
      )}`,
    },
    {
      id: 'vscode',
      label: 'VS Code',
      content: JSON.stringify(
        {
          inputs: [
            {
              type: 'promptString',
              id: VSCODE_TOKEN_INPUT_ID,
              description: 'Halo MCP access key',
              password: true,
            },
          ],
          servers: {
            [SERVER_NAME]: {
              ...installConfig,
              headers: {
                Authorization: `Bearer \${input:${VSCODE_TOKEN_INPUT_ID}}`,
              },
            },
          },
        },
        null,
        2,
      ),
      installUrl: `vscode:mcp/install?${encodeURIComponent(
        JSON.stringify({ name: SERVER_NAME, ...installConfig }),
      )}`,
    },
  ]

  return guides
}
