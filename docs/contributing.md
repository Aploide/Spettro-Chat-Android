# Contributing

Thanks for considering a contribution. This page describes the conventions the codebase
already follows, so a patch reads like the code around it.

## Before you start

- Open an issue for anything larger than a bug fix, so the approach can be agreed on first.
- Read [architecture.md](architecture.md). Most review comments come down to a change being
  in the wrong layer.

## Getting set up

See [building.md](building.md). Sign-in needs a Clerk publishable key; everything else
builds without configuration.

## Checks

```sh
./gradlew assembleDebug lint test
```

Instrumented tests (`connectedAndroidTest`) need a device or emulator.

Please make sure a release build still works when you touch serialization, Room, or
reflection-dependent code — R8 is enabled for release, and keep rules live in
`app/proguard-rules.pro`:

```sh
./gradlew assembleRelease
```

## Code style

- Kotlin official style (`kotlin.code.style=official`). Four-space indent, trailing commas
  on multi-line argument lists.
- Keep lines reasonably short; the codebase wraps at roughly 100 characters.
- **Comments explain *why*, not *what*.** The existing comments are the model: they record
  the reason a non-obvious choice was made — why alarms are inexact, why images live in
  their own table, why the foreground service ignores `onTaskRemoved`. Do not add comments
  that restate the code.
- KDoc every public class and any function whose contract is not obvious from its name.
- Match the surrounding density. This is not a heavily commented codebase, nor a bare one.

## Conventions worth knowing

**Dependency wiring.** Add new services to `AppContainer`, not to ViewModel constructors.
There is no DI framework and none is wanted.

**State.** UI-facing state is `StateFlow` exposed read-only via `asStateFlow()`, with the
mutable version private. One-shot events use `SharedFlow` with a small buffer and
`tryEmit`.

**Cancellation.** Always rethrow `CancellationException` before a broad `catch (e:
Exception)`. Several places in the engine and the MCP registry depend on this; swallowing it
breaks stop-streaming and timeouts.

**Errors reaching the model.** A tool that fails, or that the user declines, must return a
tool result that says so explicitly and tells the model not to retry. Silence makes models
guess or loop.

**Errors reaching the user.** Keep them short, plain, and actionable. Network failures get a
specific message; everything else is truncated.

**Persistence.** Any Room schema change needs an explicit `Migration`. Destructive fallback
is not acceptable — chats exist only on the device.

**Backups.** New persisted user data should be added to `SpettroBackup` with a default
value, so old files keep importing and old builds keep reading new files. Credentials never
go into a backup.

## Adding a built-in tool

1. Implement it in `data/tools/`, returning `ToolResult` and never throwing across the
   boundary.
2. Add a name constant, a `ToolSpec` (description plus a JSON Schema for the arguments), a
   `runningLabel`, a `doneLabel`, and a `execute` branch in `ToolRegistry`.
3. If it touches personal data, add a `SensitiveMeta` case with consent copy and any
   required Android permissions — and add the permission to the manifest. The engine takes
   care of the gate itself.
4. If the tool needs the app on screen, check `appVisibleProvider()` and fail softly with a
   message the model can explain.
5. If the model should know when to reach for it, mention it in the system prompt in
   `ChatEngine`.
6. Document it in [agent-and-tools.md](agent-and-tools.md) and, if it reads personal data,
   in [data-and-privacy.md](data-and-privacy.md).

Tool descriptions are prompts. Say what the tool is *for* and when to use it, not just what
it returns.

## UI work

- The palette is monochrome by design: equal RGB channels everywhere, red reserved for
  destructive actions. Use the tokens in `ui/theme/Color.kt` rather than literal colors.
- Use the shared surfaces in `ui/components/Glass.kt` and the type scale in `Type.kt`
  instead of ad-hoc values.
- Anything that streams must not re-parse the whole document per token; follow the chunking
  approach in `MarkdownBody.kt`.

## Security

- Never commit keys, tokens, keystores, or `local.properties`.
- Do not add analytics, crash reporting, or any dependency that phones home.
- Do not put message content, reasoning, or tool output into notifications.
- Report security issues privately to the maintainers rather than in a public issue.

## Licensing of contributions

The project is licensed under the GNU General Public License, version 3 or later (see
[LICENSE](../LICENSE)), with two additional terms under GPLv3 section 7
(see [LICENSE-EXCEPTION](../LICENSE-EXCEPTION)): a linking exception for the proprietary
Google Play services and Play Integrity libraries that the sign-in SDK requires, and a
trademark term covering the Spettro and Eyed® names, logos, and icons.

By opening a pull request you agree that your contribution is licensed under those same
terms.

**Branding.** The Spettro, Eyed, and Eyed® Softwares names, logos, and app icons are owned by
Carlo Esposito and Eyed® Softwares, and are not covered by the GPL — the Eyed® logo is a
registered trademark (UIBM no. 302024000146292). Don't add, alter, or repurpose brand assets
in a PR, and rebrand any fork you distribute.

**Scope.** Only this client is GPL-covered. The Spettro backend, the spettro.app web app, and
the hosted infrastructure are proprietary; patches here cannot assume anything about them
beyond the HTTP contract in `data/api/`.

**New dependencies.** Apache-2.0 and MIT/BSD/ISC dependencies are fine — they flow into
GPLv3 without friction. Note that Apache-2.0 is compatible with GPLv3 but *not* GPLv2, which
is one reason the license notice says "version 3 or later".

Do not add a dependency under a GPL-incompatible license. That includes proprietary SDKs:
the linking exception is scoped to specific named components and does not generalize, so a
new closed-source dependency would require the copyright holder to widen it — raise it in an
issue first rather than in a PR.

When you add or remove a bundled dependency, update
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) in the same PR.

## Pull requests

- One logical change per PR.
- Describe the *why* in the description; the diff already shows the what.
- Note any new permission, new network destination, or new persisted data explicitly —
  those get the closest review.
