# Data and privacy

This page documents what the app stores, where it stores it, and what leaves the device. It
describes the client only; how the Spettro backend handles requests is out of scope here.

## What is stored on the device

| Data | Location |
|---|---|
| Conversations, messages, attached images, tool runs | Room database `conversations.db` |
| Skills you create | same database |
| Memory facts | same database |
| API key | DataStore, encrypted with the Android Keystore |
| Account email, plan, and credit snapshot | DataStore (cache for instant first paint) |
| Model list | DataStore (cache) |
| UI settings: selected model, effort, animations, haptics | DataStore |
| Standing consent grants | DataStore |
| MCP server configs, including any tokens you entered | DataStore |
| Cached MCP tool lists | DataStore |
| Pending reminders | DataStore |

**Chats never leave the device except as message content sent to the model.** There is no
conversation sync API; the backend does not receive or store your chat history as a
document. Moving chats between devices is done with the export file described below.

## The API key

The Spettro API key is minted once at sign-in and returned exactly once by the server, which
stores only its hash. On the device it is encrypted with AES-256-GCM using a key generated
in — and non-exportable from — the Android Keystore, stored as `base64(iv):base64(ciphertext)`.

A decrypted copy is held in memory while the app runs, because the request path needs it
synchronously.

Signing out clears the key locally and revokes it server-side (best-effort), and ends the
Clerk session.

## What is sent off the device

| Destination | What |
|---|---|
| `api.spettro.app` | Chat completions: the system prompt, conversation history, attached images, tool specs, and tool results. Also the model list and account status. |
| `spettro.app` | Only at sign-in and sign-out: user sync, key minting, key revocation, authenticated with the Clerk session JWT. |
| Clerk | The OAuth sign-in flow itself. |
| `html.duckduckgo.com` | Search queries, only when the model calls `web-search`. |
| Any URL the model fetches | Only when the model calls `web-fetch`. |
| MCP servers you configure | Tool arguments for calls you approved, plus whatever auth header you configured. |

Attached images are downscaled to a 1568 px long edge and re-encoded as JPEG before they are
sent or stored.

Nothing is sent to analytics, crash-reporting, or advertising services. The app declares no
such dependency.

## Personal data on the device

Four tools read personal data — calendar, contacts, location, and reminders (which write a
notification). Each goes through a mandatory in-app approval card *before* the Android
runtime permission, with allow-once / always-allow / deny. Standing grants are listed and
revocable under **Settings → Tool permissions**. See
[agent-and-tools.md](agent-and-tools.md#the-consent-model).

Results of those tools are returned to the model as tool results, which means they become
part of the conversation sent on subsequent turns. Deny is always available and returns an
explicit refusal that tells the model not to retry.

## Notifications

Notifications are deliberately content-free:

- Progress notifications rebuild their label from the tool *name* alone, never from
  model-written text. (The `comment` tool's label in the UI *is* the model's message, which
  is exactly why the lock screen does not use it.)
- The completion notification names the chat and whether the run failed — never the answer
  or the reasoning.

## Backup and transfer

**Settings → Your data** exports one JSON file containing chats, user-created skills,
memory, MCP servers, standing consent grants, and UI settings.

The envelope is versioned. Version 2 is a strict superset of the older chats-only export, so
files written by earlier builds import cleanly and older builds can still read the chats out
of a version 2 file.

```jsonc
{
  "app": "spettro-chat",
  "version": 2,
  "exportedAt": 0,
  "conversations": [ /* full chats, including images as data URLs */ ],
  "skills":        [ /* user-created only; bundled skills ship with every install */ ],
  "memories":      [ { "text": "", "addedAt": 0, "usedAt": 0 } ],
  "mcpServers":    [ /* including any tokens you entered */ ],
  "toolConsentAlways": [ "tool:…", "mcp:…" ],
  "settings": { "selectedModel": "", "thinkingLevel": "", "streamingAnimations": true, "hapticFeedback": true }
}
```

**The API key is never part of a backup.** It is a per-device credential, minted at sign-in.

Import merges; it never deletes:

- Chats: an imported copy replaces a local one only when it is newer, so restoring an old
  backup cannot clobber fresher history.
- Skills: slug conflicts are skipped.
- Memories: deduplicated, original dates kept.
- MCP servers: a known id is replaced by the imported config.
- Settings: applied, and the UI reloads them without a restart.

Because an export can contain MCP tokens, full chat contents, and everything the assistant
remembers about you, treat the file as sensitive.

## Permissions declared

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | API calls; connectivity in `device-info` |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keeping a run alive off-screen |
| `POST_NOTIFICATIONS` | Progress, completion, input-needed, and reminder notifications |
| `READ_CALENDAR` | `calendar-events` reads only — creation goes through the calendar app |
| `READ_CONTACTS` | `contacts-search` |
| `ACCESS_COARSE_LOCATION` | `get-location`, city-level only |
| `RECEIVE_BOOT_COMPLETED` | Re-scheduling pending reminders after a reboot |

All four data permissions are requested at the moment of use, behind the consent card, and
never at first launch. Notification permission is asked at the first send, and denying it
never blocks a run.
