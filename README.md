# Halo MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io/) server for Halo.
It exposes native post, single-page, taxonomy, and attachment tools to MCP clients
through scoped MCP access keys. Enabled Halo plugins can
also contribute their own tools through the small, protocol-neutral MCP Server API.

## Requirements

- Halo 2.26.0 or later
- Java 21 for local development
- An HTTPS endpoint for production use
- An MCP client that supports Streamable HTTP and custom bearer tokens

The plugin uses MCP Java SDK 2.0.0 and supports protocol versions `2024-11-05`,
`2025-03-26`, `2025-06-18`, and `2025-11-25`. It does not implement the legacy
HTTP+SSE transport, the 2026 protocol era, or MCP OAuth discovery.

## Endpoint and authentication

After installing and enabling the plugin, the MCP endpoint is:

```text
https://halo.example.com/mcp
```

Open **Tools → MCP 服务** in Halo Console, generate a key, and select the exact
tools that key may call. The page also shows the endpoint, a quick client
configuration, and each tool's built-in or provider-plugin source. The management
page and API are restricted to Halo super administrators. The generated key is
displayed only once.

Requests must include the generated MCP key:

```http
Authorization: Bearer hmcp_...
```

Tool access is independent of Halo content RBAC: the key's exact tool allowlist
is the authorization boundary. Newly installed tools are denied until an
administrator explicitly adds them to a key. Disabled and expired keys are
rejected, and rotating a key invalidates its previous secret immediately.
Requests carrying an MCP Bearer token are limited to 600 per minute per observed
network source before key validation. This is an overall source-level ceiling and
includes successful requests. Tool calls are additionally limited to 120 per
minute for each access-key and tool pair. Limits are process-local and therefore
apply independently to each Halo replica.

Use a dedicated, least-privilege key. Do not put keys in URLs, configuration
files committed to source control, shell history, or logs.

Direct browser requests are rejected because the transport does not allow an
`Origin` header by default. CLI clients and server-side Inspector connections
normally omit that header.

## Tools

| Tool                                                                                   | Purpose                                                           |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `halo_search_content`                                                                  | Search posts and single pages.                                    |
| `halo_list_posts` / `halo_get_post`                                                    | List posts or read HEAD/RELEASE content.                          |
| `halo_create_post` / `halo_update_post`                                                | Create or update a post and its content snapshots.                |
| `halo_set_post_publish_state` / `halo_recycle_post`                                   | Set a post's publication state or recycle it.                     |
| `halo_list_single_pages` / `halo_get_single_page`                                      | List single pages or read HEAD/RELEASE content.                   |
| `halo_create_single_page` / `halo_update_single_page`                                  | Create or update a single page and its content snapshots.         |
| `halo_set_single_page_publish_state` / `halo_recycle_single_page`                     | Set a page's publication state or recycle it.                     |
| `halo_list_categories` / `halo_create_category` / `halo_update_category`              | List, create, or update post categories.                          |
| `halo_list_tags` / `halo_create_tag` / `halo_update_tag`                              | List, create, or update post tags.                                |
| `halo_list_comments` / `halo_set_comment_approval` / `halo_delete_comment`            | List, moderate, or delete comments.                               |
| `halo_list_comment_replies` / `halo_set_reply_approval` / `halo_delete_reply`         | List, moderate, or delete comment replies.                        |
| `halo_list_attachments` / `halo_get_attachment`                                        | List or inspect attachments.                                      |
| `halo_upload_attachment` / `halo_delete_attachment`                                    | Upload a Base64 attachment (up to 8 MiB) or delete an attachment. |

Native and plugin-contributed tools are exposed directly in `tools/list`; there
are no discovery or execution gateway tools. The response contains only the
tools selected for the current key. Category and tag updates, comment and reply
moderation, and attachment deletion accept an optional `expectedVersion`; a stale
version returns `CONFLICT` instead of overwriting a newer resource. Post and
single-page writes instead re-read and retry the latest resource because Halo
reconcilers may advance their metadata versions independently. Attachment uploads
intentionally accept inline Base64 only, avoiding server-side URL fetching and
SSRF exposure.

## Plugin integration

Halo plugins can contribute tools without making MCP Server a required runtime
dependency. See the [plugin integration guide](./dev/dev.md) for the Gradle
dependency, optional plugin manifest entry, provider implementation, lifecycle,
and verification steps.

Search, list, and lookup tools are read-only. Create, update, publish, unpublish,
and comment moderation tools are non-destructive writes; only recycle and delete
tools are annotated as destructive. Recycled content and attachments are excluded
from lists by default. `halo_get_post` and `halo_get_single_page` limit each
returned content field to 65,536 characters and report whether truncation occurred.

## Client configuration

Export the token in the client process environment:

```bash
export HALO_MCP_TOKEN='hmcp_replace_me'
```

### Codex

Add the following to `~/.codex/config.toml` or a trusted project's
`.codex/config.toml`:

```toml
[mcp_servers.halo]
url = "https://halo.example.com/mcp"
bearer_token_env_var = "HALO_MCP_TOKEN"
```

See the [official Codex MCP documentation](https://developers.openai.com/codex/mcp/)
for other client options.

### Claude Code

Add a project-scoped `.mcp.json` that reads the token from the environment:

```json
{
    "mcpServers": {
        "halo": {
            "type": "http",
            "url": "https://halo.example.com/mcp",
            "headers": {
                "Authorization": "Bearer ${HALO_MCP_TOKEN}"
            }
        }
    }
}
```

See the [official Claude Code MCP documentation](https://code.claude.com/docs/en/mcp).

### MCP Inspector

The current Inspector accepts an ad-hoc Streamable HTTP target and repeated
headers:

```bash
npx @modelcontextprotocol/inspector \
  --server-url https://halo.example.com/mcp \
  --transport http \
  --header "Authorization: Bearer ${HALO_MCP_TOKEN}"
```

Keep the Inspector protocol era on `legacy` or `auto`; Java SDK 2.0.0 does not
implement the 2026 protocol era.

## Development

Run the tests and build the plugin JAR:

```bash
bash gradlew test
bash gradlew build
```

Regenerate the Console API client after changing an API route or DTO:

```bash
bash gradlew generateApiClient
```

The generated OpenAPI document is written to
`api-docs/openapi/v3_0/mcpV1alpha1Api.json`, and the TypeScript Axios client is
written to `ui/src/api/generated`. Do not edit generated client files manually.

Run a compatible Halo development server:

```bash
bash gradlew haloServer
```

The official conformance CLI cannot add the custom Bearer header required by
this plugin. For local protocol checks, start the loopback-only authentication
proxy with a temporary least-privilege key, then run applicable server scenarios:

```bash
HALO_MCP_TOKEN='hmcp_...' node dev/conformance-proxy.mjs
npx @modelcontextprotocol/conformance@0.1.11 server \
  --url http://127.0.0.1:8091/mcp \
  --scenario server-initialize
```

Run scenarios matching the plugin's advertised capabilities, such as
`server-initialize`, `ping`, and `tools-list`. The complete conformance server
suite targets an everything-server fixture and also requires optional resources,
prompts, audio, image, sampling, and elicitation features that this plugin does
not advertise. Its localhost DNS-rebinding scenario is also scoped to servers
without authentication, whereas this endpoint always requires an MCP key and
rejects browser `Origin` headers.

The distributable JAR is generated under `build/libs/`.

## Error model

Tool failures return `isError: true` with a stable structured error code:

- `INVALID_ARGUMENT`
- `INVALID_ARGUMENTS`
- `NOT_FOUND`
- `FORBIDDEN`
- `CONFLICT`
- `SEARCH_UNAVAILABLE`
- `CONTENT_UNAVAILABLE`
- `ATTACHMENT_UNAVAILABLE`
- `INVALID_TOOL_RESULT`
- `RATE_LIMITED`
- `INTERNAL`

Search can be unavailable when Halo has no active search engine. Listing and
direct content reads remain available in that situation.

## License

[GPL-3.0](./LICENSE) © ryanwang
