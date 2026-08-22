import http from 'node:http'
import https from 'node:https'

const token = process.env.HALO_MCP_TOKEN
if (!token) {
  throw new Error('HALO_MCP_TOKEN is required')
}

const upstreamUrl = new URL(process.env.HALO_MCP_URL ?? 'http://127.0.0.1:8090/mcp')
const listenPort = Number.parseInt(process.env.HALO_MCP_PROXY_PORT ?? '8091', 10)
if (!Number.isInteger(listenPort) || listenPort < 1 || listenPort > 65535) {
  throw new Error('HALO_MCP_PROXY_PORT must be a valid port')
}
if (!['http:', 'https:'].includes(upstreamUrl.protocol)) {
  throw new Error('HALO_MCP_URL must use http or https')
}

const client = upstreamUrl.protocol === 'https:' ? https : http
const server = http.createServer((request, response) => {
  if (request.url !== '/mcp') {
    response.writeHead(404).end()
    return
  }

  const headers = {
    ...request.headers,
    authorization: `Bearer ${token}`,
    host: upstreamUrl.host,
  }
  const upstream = client.request(
    upstreamUrl,
    { method: request.method, headers },
    (upstreamResponse) => {
      response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers)
      upstreamResponse.pipe(response)
    },
  )
  upstream.on('error', () => {
    if (!response.headersSent) {
      response.writeHead(502)
    }
    response.end()
  })
  request.pipe(upstream)
})

server.listen(listenPort, '127.0.0.1', () => {
  process.stdout.write(`MCP conformance proxy listening on http://127.0.0.1:${listenPort}/mcp\n`)
})
