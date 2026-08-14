# The agent loop and tools

`ChatEngine` (`engine/ChatEngine.kt`) turns one user message into one finished answer,
however many tool calls that takes.

## The loop

```
send()
  │
  ├─ validate: model selected? vision needed? context near the limit?
  ├─ append the user message, persist, start a run (foreground service up)
  ├─ generate a chat title in the background (first message only)
  │
  └─ round 0..N
       ├─ offer tools: built-ins + load-skill/create-skill + enabled MCP tools
       ├─ stream the completion (SSE)
       │    Reasoning → reasoning panel
       │    Text      → answer body
       │    ToolCallStart / ToolCall → tool rows
       ├─ no tool calls? → done
       └─ for each call:
            ├─ ask-user      → suspend on the inline form
            ├─ load/create-skill → skills repository
            ├─ mcp__*        → consent per server, then the MCP client
            ├─ sensitive     → consent card, then the Android permission
            └─ otherwise     → run it
            append one role:"tool" result per call and re-send
```

Key properties:

- **Bounded.** After `MAX_TOOL_ROUNDS` (6) the next request goes out with no tools at all,
  so the model must answer with what it has gathered. A loop cannot run forever.
- **Streaming is throttled.** Token deltas arrive faster than markdown can re-parse without
  flicker, so stream-state publishes are batched to at most one per 80 ms, then flushed.
- **Partial work is kept.** Stopping a turn, or an error after text has arrived, persists
  what was streamed rather than discarding it.
- **Interim text survives tool rounds.** "Let me check…" stays visible above whatever the
  next round streams.
- **Rate limits are waited out.** A 429 emits a `RateLimited` state, sleeps for the
  `Retry-After` interval, and retries transparently.
- **Streaming failure is not fatal.** If stream setup fails for a reason other than 429 or
  401, the request is retried once non-streamed before the error surfaces.
- **401 is special.** It unwinds to a local sign-out rather than an error message.

Stored tool outputs are capped at 20,000 characters so a fetched page cannot bloat a chat.

## The system prompt

Assembled fresh on every turn from three parts:

1. A static base prompt: who the assistant is, that it should call tools without asking
   permission in prose, when to reach for web search, the clock, device info, and memory.
2. The **memory section**, re-read each turn, so a fact saved mid-turn appears from the
   next message onward. See [memory.md](memory.md).
3. The **active skill's** instructions, if the conversation has one. See
   [skills.md](skills.md).

## Built-in tools

Specs, running/finished labels, and dispatch all live in `data/tools/ToolRegistry.kt`;
implementations sit beside it.

| Tool | What it does | Gated |
|---|---|---|
| `web-search` | DuckDuckGo HTML search; returns titles, URLs, and snippets | — |
| `web-fetch` | Fetches a URL and strips it to readable text | — |
| `current-time` | Current date and time from the device clock, optional IANA timezone | — |
| `device-info` | Battery, charging state, connectivity, locale, timezone, model | — |
| `calendar-events` | Reads upcoming events, or opens the calendar editor pre-filled | ✓ |
| `contacts-search` | Looks up contacts by name; returns numbers and emails | ✓ |
| `set-reminder` | Schedules a local notification | ✓ |
| `get-location` | One coarse, city-level fix | ✓ |
| `save-memory` | Stores one short durable fact | — |
| `forget-memory` | Removes matching facts | — |
| `comment` | Emits a progress line visible in the transcript | — |
| `ask-user` | Presents up to four questions as a form and waits | — |
| `load-skill` | Pulls a skill's instructions mid-run | — |
| `create-skill` | Saves a new skill for the user | — |

Notes on individual tools:

- **`web-search`** scrapes DuckDuckGo's HTML endpoint and unwraps its redirect URLs. No API
  key, no third-party search dependency.
- **`web-fetch`** refuses binary content types, caps the body it reads, and truncates the
  returned text to a caller-specified length.
- **`calendar-events`** creates events through `ACTION_INSERT` rather than holding
  `WRITE_CALENDAR`: you confirm the exact event in your own calendar app, and the app never
  needs write access. Creation therefore requires the app to be on screen.
- **`set-reminder`** uses inexact alarms (`setAndAllowWhileIdle`). Exact alarms need a
  special-access permission that is denied by default on recent Android versions, and a
  reminder landing inside the OS batching window is the right trade for a chat assistant —
  the tool description tells the model that delivery is approximate. Pending reminders are
  persisted and re-scheduled by a boot receiver, since alarms do not survive a reboot.
- **`get-location`** takes one fix from the platform `LocationManager` rather than the fused
  location provider, rounds the coordinates, and only works while the app is on screen.

### `ask-user`

When a decision is genuinely yours to make, the model calls `ask-user` instead of asking in
prose. Up to four questions render as one inline form in the transcript, each with a short
header, up to eight options with descriptions, an optional recommended option, optional
preview content, multi-select, and optional free text.

The tool has no timeout — it waits on a person. Declining is an explicit tool error, never
silence, so the model responds to the refusal rather than guessing. A skipped question comes
back marked `(not answered)`, and a multi-select left empty comes back
`(none of the options)` — a decision, not silence.

## The consent model

Tools marked sensitive go through **two** gates, in order:

1. **The in-app consent card.** Mandatory, always in front of the OS permission. It states
   what the assistant asked for and offers *Allow once*, *Always allow*, or *Deny*.
2. **The Android runtime permission**, if one is needed at all.

Either refusal returns an explicit tool error telling the model not to retry and to answer
without the data. The engine also produces a plain-language label for the transcript — "You
declined — your calendar stays private".

Consent grants are keyed `tool:<name>` or `mcp:<serverId>` and, when granted permanently,
persist in preferences. Every standing grant is listed under **Settings → Tool permissions**
with a Revoke button.

`ConsentGate` and `PermissionBridge` both suspend the engine and expose a `pending` flow.
This matters because the engine may be running under the foreground service with no activity
on screen: the chat screen fires the OS permission dialog whenever a request is pending and
the app is resumed, so a request made while backgrounded simply waits until you come back —
and a "needs your input" notification tells you to.

## Conversation management

- **Compaction** asks the model for a self-contained summary and replaces the history with
  it. Images are dropped from the compaction request: they are the bulk of the context, and
  non-vision models must be able to compact too. Attached-document text stays in the
  request, so its facts survive into the summary.
- **Auto-compaction** runs the same summarization automatically at the end of any turn that
  leaves the history above 75% of the model's window (best-effort — a failure changes
  nothing, and the 85% hard stop still protects the next send). It can be turned off under
  Settings → Customization.
- **Attachments** ride on the user message: images as data URLs (vision models only), and
  documents (PDF via PdfBox text extraction, anything text-like as-is) as extracted text
  capped at 60,000 characters per file, sent inline as `<attached-file>` blocks ahead of
  the user's words.
- **Regeneration** drops the trailing assistant reply and re-runs the last user turn.
- **Title generation** runs as a separate background completion alongside the first turn, so
  the sidebar and the completion notification can name the chat. Best-effort; a failure
  leaves the first message as the title. Temporary chats stay nameless on purpose.
- **Temporary chats** are never written to the store, never listed in the sidebar, and gone
  when you leave them or close the app.
