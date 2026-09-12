---
category: "chores"
labels: "media-dataset, rclone"
process: "docs/process/product-design-and-delivery.md"
phase: "0"
type: "chore"
adr: "docs/adrs/adr-002-manifest-addressed-external-media-dataset.md"
write-id: "1787750961971-0.bfnhk4twbkr98ggk5pg"
points: "2"
title: "FT-OPS-005: Dataset carries songbook text and Suno metadata for Gemini"
priority: "P1"
status: "done"
uuid: "ft-ops-005-dataset-carries-text-and-metadata"
owner: "unassigned"
---

# FT-OPS-005: Dataset carries songbook text and Suno metadata for Gemini

## Outcome

The `gdrive:calliope-media` dataset folder is complete corpus material in one
place — media bytes, Suno JSON metadata, and the canonical songbook text — so
Gemini can refer to all of it without git access.

## Scope

- Manifest content classes widen to `mp3 | jpeg | json | md | txt`.
- `bb scripts/media.clj assemble` projects `docs/lyrics/` into
  `<root>/text/` as a regenerable copy (repository stays authoritative).
- `verify --ledger` cross-checks JSON metadata events alongside MP3/JPEG.
- Delta sync + remote parity check.

## Non-goals

- Changing what git tracks (text stays in `docs/lyrics/`, JSONs stay tracked,
  `<root>/text/` stays ignored).
- Including ledgers or lore documents in the dataset.

## Acceptance criteria

- Manifest holds all 2598 entries (1633 media + 275 JSON + 690 text).
- `verify --ledger`: `:ok true`, zero drift.
- `rclone check --one-way`: 2599 matching files (content + MANIFEST.edn),
  0 differences.
- `clojure -M:test` green including widened law/round-trip coverage.

## Verification

```bash
clojure -M:test
bb scripts/media.clj assemble
bb scripts/media.clj manifest
bb scripts/media.clj verify --ledger
bb scripts/media.clj check
```

---
Evidence 2026-08-26 (opencode local, commit 9ec8cef): assemble copied 690 songbook files into tracks/text/; manifest regenerated with widened content classes = 2598 entries (1633 media + 275 json + 690 text), 4440988097 bytes; verify --ledger :ok true with untracked-in-ledger=[], missing-from-manifest=[], bytes-drift=[] across all 2598; clojure -M:test 89 tests / 353 assertions / 0 failures; rclone sync transferred 966 files exit 0; rclone check --one-way: 2599 matching files, 0 differences.
---