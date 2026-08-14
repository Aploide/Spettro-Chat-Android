# Architecture

Spettro Chat is a single-activity Compose app with no dependency-injection framework, no
navigation library, and no local abstraction over the network layer beyond what the backend
actually needs. State lives in a small number of long-lived objects; the UI observes them.

## The dependency graph

```
MainActivity ──▶ AppViewModel ─┐
             └─▶ ChatViewModel ┴──▶ AppContainer
                                      │
        ┌─────────────────────────────┼──────────────────────────────┐
        ▼                             ▼                              ▼
   ChatEngine                    SpettroApi / SpettroWebApi     AppPrefs (DataStore)
        │                                                            │
        ├─▶ ConversationStore ──▶ ChatDatabase (Room)                └─▶ SecureStore
        ├─▶ ToolRegistry ──▶ Web/Device/Calendar/Contacts/Js/Artifact/…
        │        ├─▶ RecallIndex ──▶ EmbeddingService + EmbeddingDao
        │        └─▶ ArtifactStore (filesDir/artifacts, FileProvider)
        ├─▶ McpRegistry ──▶ McpClient (one per server)
        ├─▶ SkillsRepository ──▶ SkillDao
        ├─▶ MemoryStore ──▶ MemoryDao
        ├─▶ ConsentGate
        └─▶ PermissionBridge
```

`AppContainer` (`data/AppContainer.kt`) is the composition root: a process-wide singleton
that constructs every service once and wires them together. ViewModels take the container,
never individual dependencies, so adding a service does not ripple through constructors.

Two app-scoped `SharedFlow`s carry cross-cutting events:

- `unauthorized` — emitted whenever any API call returns 401; `AppViewModel` collects it and
  performs a local sign-out.
- `settingsChanged` — emitted after a backup import rewrites preferences behind the
  ViewModels' back, so UI state reloads without a restart.

## Layers

### `data/` — persistence and I/O

| Component | Responsibility |
|---|---|
| `AppPrefs` | DataStore Preferences: encrypted API key, cached account and model list, UI settings, consent grants, scheduled reminders, MCP config and tool cache |
| `SecureStore` | AES-256-GCM via the Android Keystore; protects the API key at rest |
| `SpettroApi` | Authenticated backend calls: model list, account, OpenAI-compatible chat completions with SSE streaming |
| `SpettroWebApi` | Sign-in-time calls to the website API, authenticated with a Clerk session JWT |
| `ChatDatabase` | Room schema (conversations, messages, message images, skills, memories) with explicit migrations |
| `ConversationStore` | Domain-level read/write of conversations, plus merge-on-import semantics |
| `BackupManager` | Whole-app JSON export/import |
| `ToolRegistry` | Tool specs, labels, dispatch, and sensitivity metadata |
| `McpRegistry` / `McpClient` | Remote MCP servers and a minimal Streamable HTTP client |
| `SkillsRepository`, `MemoryStore` | Skills and cross-chat memory |
| `RecallIndex` / `EmbeddingService` | On-device semantic index over chats and memory; powers `search-history` |
| `ArtifactStore` | Files the assistant generates; app-private, shared via FileProvider |

Room migrations are written by hand and never fall back destructively: chats exist only on
the device, so a dropped table is permanent data loss. The database is at version 5
(`conversations.db`).

Message images are stored in their own table rather than inline. A single data URL can
approach a megabyte, and SQLite's `CursorWindow` limit (~2 MB per row) would break on a
message carrying several.

### `engine/` — the agent

| Component | Responsibility |
|---|---|
| `ChatEngine` | The agent loop; owns conversation state and stream state |
| `AgentRunner` | The same loop headless, for background and scheduled tasks |
| `TaskManager` | Concurrent background tasks; results become chats + notifications |
| `ChatRunService` | Foreground service that keeps a run alive off-screen |
| `ConsentGate` | In-app approval for tools that touch personal data |
| `PermissionBridge` | Relays Android runtime-permission requests to whatever activity is on screen |
| `AgentNotifications` | Notification channels and builders |

`ChatEngine` is app-scoped, not ViewModel-scoped: it runs on its own `CoroutineScope`, so a
turn survives configuration changes and the activity being destroyed. `ChatViewModel` is a
thin delegate whose members mirror the engine's one-to-one.

See [agent-and-tools.md](agent-and-tools.md) for the loop itself.

### `vm/` — UI-facing state

`AppViewModel` owns authentication, account, models, and settings. `ChatViewModel` exposes
the engine plus one-shot UI concerns (export/import notices, skill save errors).

`StreamState` is the sealed type the chat screen renders:

```
Idle · Thinking(reasoning, tools) · Streaming(text, reasoning, tools)
     · RateLimited(retryAfterSeconds) · Compacting · Error(message)
```

`ContextEstimator` lives alongside it: a deliberately rough accounting (~4 characters per
token, a flat estimate per image) used to block a send before it would overflow the model's
window, and to offer compaction instead.

### `ui/` — Compose

- `ui/chat/` — the chat screen, sidebar, composer, model sheet, tool rows, ask-user form,
  consent card, markdown renderer.
- `ui/settings/` — settings sheet and the skills, MCP, and memory editors.
- `ui/auth/` — the sign-in screen.
- `ui/components/` — shared surfaces, controls, and the plan badge.
- `ui/theme/` — the monochrome palette and type scale.

The design language is deliberately monochrome: every color has equal RGB channels, with
red reserved exclusively for destructive actions. The one exception is the plan badge, which
mirrors the tier colors used by the other Spettro front-ends.

Markdown is split into stable block-level chunks so that only the growing last chunk
re-parses while tokens stream in; without this, long answers flicker.

## Background execution

An agent run must survive the app leaving the screen — the whole point of a tool loop is
that it takes time.

1. `ChatEngine.beginRun()` sets `isRunning` and starts `ChatRunService`.
2. The service immediately calls `startForeground` with type `dataSync` (the run *is* a
   network data transfer: an SSE stream plus tool HTTP calls). It is started only from user
   interactions — send, compact, regenerate — so the foreground-start restriction never
   applies.
3. While running, the service narrates progress in the ongoing notification, rebuilt from
   the tool *name* alone so no model-written text reaches the lock screen.
4. When the app is not visible, it also posts completion and "needs your input"
   notifications. Completion notifications carry the chat title and never message content.
5. The service winds down only when the interactive turn *and* every background task are
   idle (`engine.isRunning` ORed with `taskManager.anyRunning`). With no interactive turn,
   the ongoing notification narrates the task count instead of tool activity.

Scheduled tasks take a different path: WorkManager wakes the process at the scheduled time
and runs the headless loop inside its worker execution window (no foreground promotion
needed — a bounded 6-round run fits comfortably), then re-enqueues the next occurrence for
recurring tasks. WorkManager persists its queue, so schedules survive reboots without the
boot receiver's help.

Swiping the app away does not stop a run: finishing the task is the point, and the
completion notification is the way back in. On Android 15+, `onTimeout` winds a run down
cleanly when the daily `dataSync` budget is exhausted, persisting whatever partial answer
had arrived.

Visibility itself is tracked once, by a `ProcessLifecycleOwner` observer in
`SpettroChatApp`, and read by both the engine and the tool registry.

## Authentication flow

1. The Clerk Android SDK runs a Google or GitHub OAuth flow in a Custom Tab.
2. On success, the app exchanges the Clerk session JWT for a Spettro API key by calling
   `POST /api/sync-user` (idempotent; creates the user row and a free subscription on first
   login) and `POST /api/keys/generate` on the website.
3. The raw key is returned exactly once, so it is persisted synchronously, encrypted with
   the Keystore.
4. All subsequent traffic authenticates with that key as a bearer token.
5. Signing out revokes the key server-side and ends the Clerk session, both best-effort. A
   forced sign-out (401) clears local state but keeps the Clerk session, so signing back in
   is a single tap.

Sign-in requires a Clerk publishable key at build time; see [building.md](building.md).
