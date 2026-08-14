# Documentation

Documentation for the Spettro Chat Android client. Start with the
[project README](../README.md) for what the app is and how to build it.

| Document | Contents |
|---|---|
| [architecture.md](architecture.md) | Layers, dependency graph, state flow, background execution, auth flow |
| [building.md](building.md) | Build commands, sign-in configuration, debug URL overrides, release builds |
| [agent-and-tools.md](agent-and-tools.md) | The agent loop, every built-in tool, the two-stage consent model |
| [mcp.md](mcp.md) | Connecting remote MCP servers: transport, namespacing, caching, consent |
| [skills.md](skills.md) | Writing, applying, and generating skills |
| [memory.md](memory.md) | How facts are saved, deduplicated, and injected into context |
| [data-and-privacy.md](data-and-privacy.md) | What is stored, what leaves the device, the backup format, permissions |
| [contributing.md](contributing.md) | Conventions, code style, how to add a tool |

## Quick orientation

- The **agent loop** is `engine/ChatEngine.kt`. Everything else in `engine/` supports it:
  keeping it alive off-screen, and getting the user's approval when it needs it.
- The **composition root** is `data/AppContainer.kt`. If you are looking for where something
  is constructed, it is there.
- **Tools** are declared and dispatched in `data/tools/ToolRegistry.kt`; implementations sit
  beside it.
- **Persistence** is Room (`data/store/ChatDatabase.kt`) for content and DataStore
  (`data/AppPrefs.kt`) for settings and credentials.
