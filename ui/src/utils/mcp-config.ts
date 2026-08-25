export function mcpEndpoint() {
  return `${window.location.origin}/mcp`
}

const SERVER_NAME = 'halo'
const TOKEN_ENV_VAR = 'HALO_MCP_TOKEN'
const VSCODE_INPUT_ID = 'halo-mcp-token'

export type McpClientId = 'claude-code' | 'codex' | 'cursor' | 'vscode'

export interface McpClientGuide {
  id: McpClientId
  label: string
  content: string
  installUrl?: string
}

/**
 * Guides never receive the plaintext token: CLI and file-based clients read it from the
 * HALO_MCP_TOKEN environment variable at runtime, and VS Code prompts for it through its
 * secure input storage on first start.
 */
export function mcpClientGuides(): McpClientGuide[] {
  const endpoint = mcpEndpoint()

  const cursorServer = {
    type: 'http',
    url: endpoint,
    headers: { Authorization: `Bearer \${env:${TOKEN_ENV_VAR}}` },
  }

  const vscodeInputs = [
    {
      type: 'promptString',
      id: VSCODE_INPUT_ID,
      description: 'Halo MCP 密钥',
      password: true,
    },
  ]
  const vscodeServer = {
    type: 'http',
    url: endpoint,
    headers: { Authorization: `Bearer \${input:${VSCODE_INPUT_ID}}` },
  }

  return [
    {
      id: 'claude-code',
      label: 'Claude Code',
      content: `claude mcp add --transport http ${SERVER_NAME} ${endpoint} --header 'Authorization: Bearer \${${TOKEN_ENV_VAR}}'`,
    },
    {
      id: 'codex',
      label: 'Codex',
      content: `# ~/.codex/config.toml
[mcp_servers.${SERVER_NAME}]
url = "${endpoint}"
bearer_token_env_var = "${TOKEN_ENV_VAR}"`,
    },
    {
      id: 'cursor',
      label: 'Cursor',
      content: JSON.stringify({ mcpServers: { [SERVER_NAME]: cursorServer } }, null, 2),
      installUrl: `cursor://anysphere.cursor-deeplink/mcp/install?name=${SERVER_NAME}&config=${window.btoa(JSON.stringify(cursorServer))}`,
    },
    {
      id: 'vscode',
      label: 'VS Code',
      content: JSON.stringify(
        { inputs: vscodeInputs, servers: { [SERVER_NAME]: vscodeServer } },
        null,
        2,
      ),
      installUrl: `vscode:mcp/install?${encodeURIComponent(
        JSON.stringify({ name: SERVER_NAME, ...vscodeServer, inputs: vscodeInputs }),
      )}`,
    },
  ]
}
