---
id: ADR-002
title: "Manifest-addressed external media dataset, decoupled from git LFS"
status: accepted
date: "2026-08-25"
accepted: "2026-08-26"
deciders: [Err]
research: "none — user direction 2026-08-25; manifest precedent references/heresy-between/RENDERS-MANIFEST.edn"
process: "docs/process/product-design-and-delivery.md"
---

# ADR-002: Manifest-addressed external media dataset

## Context

The corpus media under `tracks/` (about 4.2 GB across 1633 MP3/JPEG files in 342
song directories) is stored through GitHub LFS. This couples byte storage to
GitHub, costs LFS quota and bandwidth on every change, and makes a checkout
dependent on an LFS-capable client before a single song can play. The small
Suno-export JSON metadata files (275 files, plain git) and all ledgers and
projections are fine in git; only the large binary bytes are the problem.

The repo already carries the pattern this decision generalizes:
`references/heresy-between/RENDERS-MANIFEST.edn` records hash, size, and
dataset-relative path for render bytes that deliberately stay outside git, and
`resources/reconstruction/path-roots.edn` translates stale roots at read time
instead of rewriting history. Err directed (2026-08-25) that LFS be dropped,
the bytes be synced to a personal Google Drive via rclone, and that the
software accept "any old folder that looks right" as the dataset.

## Decision

### 1. The media dataset is a self-describing directory, not a git object

A dataset is any directory whose layout satisfies this contract:

```text
<root>/
  MANIFEST.edn          line-oriented EDN; first line is the envelope
  <slug>/<sha8>.<ext>   media bytes (mp3, jpeg), content-addressed names
```

`MANIFEST.edn` line 1 is the envelope
`{:dataset/id "calliope-media" :schema :calliope.media/manifest-v1 :entries N
:bytes-total M :generated "<iso-8601>"}`; every following line is one entry
`{:path "<slug>/<sha8>.<ext>" :bytes N :sha256 "<64-hex>"}`, sorted by path.
The manifest is a regenerable projection over the bytes (never hand-edited
truth), and it travels with the dataset so a bare folder self-describes.

### 2. Root resolution is explicit and portable

Consumers resolve the dataset root in this order:

1. `CALLIOPE_MEDIA_ROOT` environment variable (any folder anywhere);
2. default: the repository's `tracks/` directory.

`bb scripts/media.clj where` prints the resolved root and its provenance. No
other implicit discovery exists; a missing manifest is a loud error, never a
silent empty result.

### 3. Git tracks text truth only; bytes are synced by rclone

- `.gitattributes` no longer routes `tracks/**/*.mp3` or `tracks/**/*.jpeg`
  through LFS; `.gitignore` ignores those patterns in the working tree.
- `tracks/MANIFEST.edn` and the per-track `*.json` Suno metadata stay tracked,
  so remote harnesses (GitHub connectors) retain metadata access without any
  byte access.
- `bb scripts/media.clj sync` pushes exactly the manifest-addressed files to
  the rclone remote (default `gdrive:calliope-media`, overridable by
  `--remote` or `CALLIOPE_MEDIA_REMOTE`), and `check` verifies remote parity.
  rclone's filter semantics mean stale remote files are not auto-deleted;
  removals are deliberate human acts.

### 4. The exit from LFS is forward-only

History keeps its LFS pointers; the migration untracks media going forward
(`git rm --cached` + attribute/ignore changes) and does not rewrite history.
Consequence: the ~4.2 GB already in GitHub LFS storage remains there until a
separate, explicitly approved purge. That purge is deferred, not decided.

### 5. Ledger identity vs. dataset paths

New `:track/discovered` events record `:dest` as a dataset-relative path
(`<slug>/<sha8>.<ext>`) and add `:sha256` (full) and `:dataset/id`, keeping
`:sha8`, `:src`, `:bytes`, `:asset`, `:slug` unchanged. Historical events with
`tracks/`-prefixed `:dest` values are normalized at read time by stripping one
leading `tracks/` (`calliope.media.dataset/normalize-dest`). Asset identity is
the hash; the path is a location, and locations translate, not rewrite.

## Consequences

### Amendment 2026-08-26 — dataset carries text and metadata too

Err directed that the dataset also carry the songbook text and the Suno JSON
metadata so a single Drive folder is complete for Gemini reference. The
manifest's content classes widen from media-only to
`mp3 | jpeg | json | md | txt`; `bb scripts/media.clj assemble` projects the
canonical `docs/lyrics/` songbook into `<root>/text/` (repository stays the
authority; the copy is regenerable); `verify --ledger` now cross-checks JSON
metadata events alongside MP3/JPEG. The git side does not change: text lives
in `docs/lyrics/`, JSONs stay tracked, `<root>/text/` stays ignored.

## Original consequences

- A fresh machine reconstructs a working checkout with: `git clone`, then
  `rclone sync gdrive:calliope-media <any-folder>` (or into `tracks/`), then
  optionally set `CALLIOPE_MEDIA_ROOT`. `bb scripts/media.clj verify --ledger`
  proves byte-for-byte integrity against both the manifest and the ingest
  ledger.
- `corpus.clj tracks` writes into the resolved dataset root, emits full-hash
  events, and regenerates the manifest; the operator then commits the manifest
  diff and runs `sync`.
- GitHub LFS storage remains billed until the deferred purge happens; new
  pushes add no LFS objects.
- FT-001A (playable media indexing) consumes `calliope.media.dataset` instead
  of touching `tracks/` paths directly, and inherits missing/changed-file
  states from `verify`.
- The heresy-between renders dataset stays on its own manifest
  (`RENDERS-MANIFEST.edn`); this ADR does not absorb it.
