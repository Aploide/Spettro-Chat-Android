# Spettro Chat for Android

A native Android client for [Spettro](https://spettro.app): a chat app that is also a small
agent. It streams answers from the models on your Spettro plan, calls tools on your behalf
(web, calendar, contacts, reminders, location, remote MCP servers), remembers facts across
chats, and keeps working when you leave the screen.

Built entirely with Kotlin and Jetpack Compose, with no analytics, crash reporting, or ad
SDKs of any kind.

---

## What it does

**Chat**
- Streaming answers with live token rendering and an optional collapsible reasoning panel.
- Image attachments (auto-downscaled and re-encoded) for vision-capable models.
- Per-chat model and reasoning-effort selection, remembered between launches.
- Pin, archive, search, rename-by-generation — every chat gets an auto-generated title.
- **Temporary chats** that live only in memory and are never written to disk.
- **Compaction**: summarize a long conversation in place when it nears the model's context
  window, instead of failing mid-turn.
- Markdown rendering with copyable code blocks; per-message copy and regenerate.

**Agent**
- A real tool loop: the model can call tools, read the results, and call more, up to a
  bounded number of rounds before it must answer.
- Built-in tools for web search and page fetching, the device clock, device status,
  calendar, contacts, reminders, coarse location, persistent memory, progress comments,
  and a structured **ask-user** form when a decision is genuinely yours to make.
- Remote **MCP servers** (Streamable HTTP) you configure yourself; their tools are offered
  to the model alongside the built-ins.
- **Skills**: reusable instruction sets applied per chat via a `/slug`, editable in
  Settings, and creatable by the assistant itself.
- **Memory**: short durable facts saved across chats, editable by you at any time.
- Runs keep going in the background under a foreground service, with progress,
  completion, and "needs your input" notifications.

**Privacy**
- Every tool that touches personal data goes through an in-app approval card *before* the
  Android runtime permission, with allow-once / always-allow / deny, and standing grants
  you can revoke in Settings.
- Chats live only on the device (Room/SQLite). There is no conversation sync.
- The API key is encrypted at rest with the Android Keystore.
- Whole-app export/import to a single JSON file — chats, skills, memory, MCP servers,
  settings — with the credential deliberately left out.

---

## Requirements

| | |
|---|---|
| Android | 10 (API 29) or newer |
| Build JDK | 17 |
| Compile / target SDK | 37 |
| Gradle | wrapper included (`./gradlew`) |
| Account | a [Spettro](https://spettro.app) account for models and billing |

## Building

```sh
./gradlew assembleDebug
```

Or open the project in Android Studio and run the `app` configuration.

A debug build compiles and runs with no extra configuration, but sign-in stays disabled
until you supply a Clerk publishable key — see [docs/building.md](docs/building.md) for
that, for local-backend URL overrides, and for release/R8 notes.

> **No secrets live in this repository.** The Clerk publishable key is instance-specific
> and is injected at build time from `local.properties` (gitignored), a Gradle property, or
> an environment variable. Never commit it, and never commit `local.properties`.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layers, dependency graph, state flow, background execution |
| [docs/building.md](docs/building.md) | Build, configuration, debug overrides, release builds |
| [docs/agent-and-tools.md](docs/agent-and-tools.md) | The tool loop, every built-in tool, the consent model |
| [docs/mcp.md](docs/mcp.md) | Connecting remote MCP servers, namespacing, consent |
| [docs/skills.md](docs/skills.md) | Writing, applying, and generating skills |
| [docs/memory.md](docs/memory.md) | How facts are saved, deduplicated, and injected |
| [docs/data-and-privacy.md](docs/data-and-privacy.md) | What is stored, where, and what leaves the device |
| [docs/contributing.md](docs/contributing.md) | Conventions, code style, how to add a tool |

## Project layout

```
app/src/main/java/to/eyed/spettro/chat/
├── MainActivity.kt          Single activity; hosts the Compose tree
├── SpettroChatApp.kt        Application: Clerk init, channels, visibility tracking
├── data/
│   ├── AppContainer.kt      Manual DI — one instance of every service
│   ├── AppPrefs.kt          DataStore: credential, caches, settings, grants
│   ├── ImageUtil.kt         Attachment downscaling and data-URL encoding
│   ├── api/                 Spettro backend clients and wire models
│   ├── auth/                Keystore-backed encryption for the API key
│   ├── mcp/                 MCP client, registry, and models
│   ├── memory/              Cross-chat memory store
│   ├── skills/              Bundled and user skills
│   ├── store/               Room database, conversation store, backup manager
│   └── tools/               Built-in tool implementations and the registry
├── engine/                  Agent loop, foreground service, consent, permissions
├── ui/                      Compose UI: chat, settings, auth, components, theme
└── vm/                      ViewModels and UI-facing state types
```

## Contributing

Issues and pull requests are welcome. Please read
[docs/contributing.md](docs/contributing.md) first — it covers the conventions the codebase
follows and the checks to run before opening a PR.

## Security

If you find a security issue, please report it privately to the maintainers rather than
opening a public issue.
