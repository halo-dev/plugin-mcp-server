export function mcpEndpoint() {
  return `${window.location.origin}/mcp`
}

const SERVER_NAME = 'halo'
const TOKEN_PLACEHOLDER = '$HALO_MCP_TOKEN'

export function mcpHttpConfig(token = TOKEN_PLACEHOLDER) {
  return JSON.stringify(
    {
      mcpServers: {
        [SERVER_NAME]: {
          type: 'http',
          url: mcpEndpoint(),
          headers: {
            Authorization: `Bearer ${token}`,
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

export function mcpClientGuides(token?: string): McpClientGuide[] {
  const endpoint = mcpEndpoint()
  const bearer = `Bearer ${token ?? TOKEN_PLACEHOLDER}`
  const serverConfig = {
    type: 'http',
    url: endpoint,
    headers: { Authorization: bearer },
  }

  const guides: McpClientGuide[] = [
    {
      id: 'claude-code',
      label: 'Claude Code',
      content: `claude mcp add --transport http ${SERVER_NAME} ${endpoint} --header "Authorization: ${bearer}"`,
    },
    {
      id: 'codex',
      label: 'Codex',
      content: `# ~/.codex/config.toml
[mcp_servers.${SERVER_NAME}]
url = "${endpoint}"
http_headers = { Authorization = "${bearer}" }`,
    },
    {
      id: 'cursor',
      label: 'Cursor',
      content: mcpHttpConfig(token),
    },
    {
      id: 'vscode',
      label: 'VS Code',
      content: JSON.stringify({ servers: { [SERVER_NAME]: serverConfig } }, null, 2),
    },
  ]

  if (token) {
    for (const guide of guides) {
      if (guide.id === 'cursor') {
        const config = window.btoa(JSON.stringify(serverConfig))
        guide.installUrl = `cursor://anysphere.cursor-deeplink/mcp/install?name=${SERVER_NAME}&config=${config}`
      }
      if (guide.id === 'vscode') {
        const config = encodeURIComponent(JSON.stringify({ name: SERVER_NAME, ...serverConfig }))
        guide.installUrl = `vscode:mcp/install?${config}`
      }
    }
  }

  return guides
}
