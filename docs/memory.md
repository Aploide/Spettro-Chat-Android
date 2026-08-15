# Memory

Memory is a small set of short, durable facts that persist across chats — your name, the
languages you work in, standing preferences, ongoing projects. It is stored on the device
and editable by both you and the assistant.

## What gets saved

The model calls `save-memory` when it learns something worth remembering *across*
conversations, one short line per fact, and `forget-memory` when you correct or retract
something. Transient details of the task at hand are explicitly out of scope.

A fact must be a single line of at most 500 characters. Anything else is rejected with a
reason the model can act on.

## Deduplication

Saving is not a blind append:

- **Exact match** (after normalization — lowercased, punctuation stripped, whitespace
  collapsed): the existing fact's last-used date is bumped instead of storing a duplicate.
- **Near-duplicate**: the new wording replaces the old one. Two facts are near-duplicates
  when their normalized token sets have Jaccard overlap ≥ 0.8, or when they share the same
  three leading tokens — the same subject with a different tail, like "prefers tabs" versus
  "prefers spaces".
- **Otherwise**: stored as a new fact.

The outcome is reported back to the model, so it knows whether it created, refreshed, or
superseded something.

Forgetting removes every fact whose normalized text equals the query, or — failing an exact
match — contains it, and reports what was removed.

## Injection into context

Memory is re-read on **every turn** and rendered as a `# Memory` section appended to the
system prompt. A fact saved mid-turn is therefore in context from the next message onward.

Facts are injected most-recently-used first, under an 8 KB cap. When memory exceeds the cap,
the stalest facts are the ones dropped.

## Recall

The 8 KB injection above is what the model *always* sees. Everything else — the full text of
past conversations, and memory facts that fell off the cap — is reachable on demand through
the `search-history` tool, backed by an on-device embedding index (`data/recall/`).

- **What is indexed.** Memory facts, and user/assistant messages of every saved chat, split
  into ~700-character paragraph-aligned chunks. Temporary chats are never saved, so they are
  never indexed. Chunks are content-addressed (the row key embeds a hash of the chunk), so
  indexing is an incremental set difference: deleting a chat drops its rows, compaction
  replaces them, unchanged chunks cost nothing. The index catches up in the background at
  startup and after every finished turn, and always right before a search.
- **The embedder.** A dependency-free hashed-feature embedder (word unigrams + character
  trigrams, 256 dims) gives lexical matching — no ML runtime, no model download, nothing
  native to crash. Vectors are stamped with the embedder id, so if the scheme ever changes
  the index rebuilds itself on the next catch-up.
- **Search** embeds the query, brute-forces cosine over the index (phone-scale corpora need
  no ANN structure), adds a small keyword-overlap bonus, caps hits at two per conversation,
  and excludes the active chat — its content is already in context. Hits return as snippets
  with chat titles and dates.
- **Privacy.** The index lives in the same Room database as the chats it is derived from,
  and nothing about indexing or search leaves the device. It is derived data, so backups
  skip it; it is rebuilt from the imported chats on the next catch-up.

## Your controls

**Settings → Connectors → Memory** is the review surface. You can add, edit, and delete
individual facts, or clear everything. Editing keeps a fact's original added-date; editing
into a wording that already exists merges the two.

Memory is included in a backup export and merged on import, keeping the original dates.
Imported facts skip near-duplicate replacement — a backup is already-reviewed data.
