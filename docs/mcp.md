# MCP servers

The app can call tools hosted on remote [Model Context Protocol](https://modelcontextprotocol.io)
servers. You configure them yourself under **Settings → Connectors → MCP servers**; their
tools are then offered to the model alongside the built-ins.

## What is supported

- **Transport:** Streamable HTTP only. There is no stdio transport — nothing on a phone can
  spawn a local process — and no separate SSE-transport implementation.
- **Protocol version:** `2025-06-18`.
- **Methods:** `initialize` / `notifications/initialized`, `tools/list` (with pagination),
  `tools/call`.
- **Not supported:** resources, prompts, sampling, and any server-initiated request. The
  client advertises no capabilities and ignores messages the server starts.

Responses are accepted as either `application/json` or `text/event-stream` carrying the
JSON-RPC messages.

## Configuring a server

Each server has a name, a URL, and optional authentication:

- `bearerToken` — sent as `Authorization: Bearer …` when non-blank.
- `headerName` / `headerValue` — one extra header, for servers that use an API-key header.

Servers can be enabled and disabled individually. The settings sheet also shows each
server's discovered tools, its last error, and a refresh action.

> Tokens you enter are stored in app preferences on the device and are included in a backup
> export. Treat an exported backup file as a credential-bearing document.

## How tools reach the model

Tool names are namespaced as `mcp__<serverslug>__<tool>`, sanitized to the function-name
character set and capped at 64 characters. A reverse map is the source of truth for
dispatch, so a truncation collision can only shadow a duplicate name — it can never
mis-route a call. Descriptions are prefixed with the server name so the model knows where a
tool comes from.

Each server contributes at most 50 tools.

## Listing and caching

Server configs and their last known tool lists are cached in preferences, using the same
pattern as the model list, so tools are offered instantly on later launches without a
round-trip.

Live listing happens lazily on the first send of a session, and explicitly when you refresh
from the settings sheet. Listing runs under a 10-second budget per server.

**A dead server never blocks a send.** A failure or timeout records an error against that
server, contributes no tools for the turn, and is shown in the settings sheet. Sessions are
re-established automatically if a server drops them: a 404 with a live session id triggers
one re-handshake before the call is retried.

## Consent

MCP calls go through the same mandatory in-app consent card as sensitive built-in tools,
granted **per server** — one approval covers every tool on that server. The card names the
tool being called and the server's URL, so the decision is informed.

The grant key is `mcp:<serverId>`. Removing a server also revokes its standing grant.
Grants are listed and revocable under **Settings → Tool permissions**.

## Output handling

`tools/call` results are flattened to text: `text` content blocks and the text of embedded
`resource` blocks are joined; other block types are dropped. The result is capped at 20,000
characters. A server's `isError` flag is carried through to the model, and transport
failures are returned as tool errors rather than crashing the turn.
