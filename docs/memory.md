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

## Your controls

**Settings → Connectors → Memory** is the review surface. You can add, edit, and delete
individual facts, or clear everything. Editing keeps a fact's original added-date; editing
into a wording that already exists merges the two.

Memory is included in a backup export and merged on import, keeping the original dates.
Imported facts skip near-duplicate replacement — a backup is already-reviewed data.
