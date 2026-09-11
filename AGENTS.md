# calliope — Repository Agent Guide

A muse/eta-mu/epiphany-participant workspace for the Calliope corpus: roughly
690 unique songs distilled from about 1,500 lyric files, now connected to Suno
track metadata, audio, artwork, classifier programs, and provisional concept
work.

Read `PROCESS.md` first. It governs evidence, receipts, harness portability,
completion claims, and acceptance. This guide describes repository facts and does
not imply access to Err's machine.

## Ground truth

- `ledgers/ingest.edn` — append-only ingestion truth. Events preserve path,
  content hashes, classification, title, basis, and run provenance.
- `docs/lyrics/` — derived pass-1 projection. Never edit by hand to create new
  truth; change sources or projection logic and regenerate.
- `docs/lyrics/index.edn` and `ledgers/projections/songs-v1.edn` — song index
  projections.
- `ledgers/projections/variants-v1.edn` — pass-2 same-title similarity signals.
  Similarity never silently becomes identity.
- `tracks/` — the media dataset mount point (see ADR-002). `MANIFEST.edn` and
  the per-track `*.json` Suno metadata are git-tracked; MP3/JPEG bytes and the
  `text/` songbook projection are untracked dataset content synchronized
  externally via rclone (`bb scripts/media.clj`). Any folder with a valid
  `MANIFEST.edn` works as the dataset when selected via `CALLIOPE_MEDIA_ROOT`.
  Track ingestion into an external root also projects its full manifest and
  verified JSON metadata into repository `tracks/` for the next git commit.
- `src/calliope/media/dataset.cljc` — pure dataset library (root resolution,
  manifest read/write, verification).
- `resources/classifiers/` — pure-data classifier and feature-extractor programs.
- `src/calliope/law/` — Malli contracts only.
- `src/calliope/classifier/` — DSL validation and JVM runtime adapters.
- `docs/lore/` — derived thematic, stylistic, and world-building synthesis.
- `docs/research/` — evidence and constraints; research does not decide architecture.
- `docs/adrs/` — architectural decision records.
- `docs/designs/` — approved product behavior.
- `docs/process/` — delivery policies implementing the root charter.
- `docs/kanban/` — Rheos card corpus only: stories, epics, and chores.
- `docs/kanban-docs/` — board prose, contract, and delivery map outside `tasksDir`.
- `openhax.kanban.json` — board discovery and FSM configuration.
- `receipts.edn` — append-only Receipt River accountability ledger.

## Audio reconstruction

- `docs/reconstruction/` — operating model, contracts, rubrics, runtime split, and tool surveys for recovering owned renders as inspectable local music.
- `resources/reconstruction/` — pure-data rubrics, path-root translations, handoff schemas, and agent contracts.
- `src/fork_tales/law/audio.cljc` and `src/fork_tales/law/reconstruction.cljc` — reconstruction contracts and event law.
- `scripts/reconstruction/` — canonical validation, preflight, grading, metrics, and Gemma-check programs.
- `references/heresy-between/` — committed evidence and manifests; large regenerable render bytes remain local and are referenced by hash.
- `ledgers/reconstruction.edn` — append-only reconstruction events, created by the first lane that appends one. Historical evidence is never rewritten merely to repair stale paths; translate through recorded path-root rules.

Preflight before grading. A grader handed unreachable evidence does not error; it
under-reports coverage, so broken input reads as a weak candidate. See
`docs/reconstruction/README.md`.

## Commands

These require a local checkout, shell, dependencies, and applicable services.
Their presence does not prove the current harness can execute them.

```bash
bb scripts/corpus.clj ingest
bb scripts/corpus.clj project
bb scripts/corpus.clj stats
bb scripts/corpus.clj variants

clojure -M:test
clojure -M:classify -- --seed 3721599729 --dry-run
clojure -M:classify -- --seed 3721599729

# Rheos discovers openhax.kanban.json from the repository root.
eta-mu kanban count
eta-mu kanban list
eta-mu kanban find ft-000b-define-media-workbench-domain-laws

# Media dataset (ADR-002). Root: CALLIOPE_MEDIA_ROOT, default tracks/.
bb scripts/media.clj where
bb scripts/media.clj assemble
bb scripts/media.clj manifest
bb scripts/media.clj verify [--no-hash] [--ledger]
bb scripts/media.clj sync [--remote <rclone-remote:path>]
bb scripts/media.clj check [--remote <rclone-remote:path>]

# Reconstruction lanes. Preflight first; both exit non-zero on failure.
bb scripts/reconstruction/preflight.clj EVIDENCE...
bb scripts/reconstruction/validate.clj PACKET...
```

Use `--tasks-dir` only for an intentional override or discovery diagnostic.

A non-dry classifier run also requires its declared endpoint and models. An
unreachable service is unavailable, never a successful empty result.

## Classifier architecture

```text
source objects
  -> seeded selection
  -> feature extraction and exact cache lookup
  -> bounded context
  -> model prompt and validated output
  -> derived feature or provisional concept events
```

Keep source versus projection, work versus section/span, production intent versus
audible observation, feature observation versus classification, and provisional
versus accepted relation distinct. Classifier programs remain non-executable data
with closed operation vocabularies and explicit resolvers.

## Accepted media-workbench authority

The governing set is:

- research: `docs/research/media-workbench-interface-and-publishing.md`;
- accepted ADR: `docs/adrs/adr-001-local-first-media-workbench.md`;
- approved design: `docs/designs/media-workbench-v1.md`;
- accepted process: `docs/process/product-design-and-delivery.md`;
- board contract: `docs/kanban-docs/AGENTS.md`;
- delivery map: `docs/kanban-docs/BOARD-BREAKDOWN.md`.

Preserve these boundaries:

- a work is not a render;
- a render is immutable source audio;
- a marker is an annotation, not automatically an accepted edit;
- a clip is a non-destructive span of one render;
- an arrangement is an edit decision list;
- an export is a rebuildable derivative;
- a release is a locally accepted bundle before publication;
- publication state is target-specific;
- playlists, smart lists, media workspaces, and the Rheos board are distinct.

The first client is a native Clojure/JVM desktop application with no embedded
browser. The native UI, audio backend, read model, and in-process application
topology are owned by FT-000D and must be verified against real corpus audio.

The first usable milestone is a daily-driver player. Publishing work may not delay
playback, curation, or salvage.

## Rheos board discipline

- **Rheos is the sole implementation and operational authority for board state:**
  status, frontmatter, comments, and transitions.
- Never create a repository-local parser, validator, migration script, sidecar,
  workflow implementation, or alternate command surface for Rheos semantics.
- CI and agents invoke eta-mu/Rheos directly. A check that cannot be expressed by
  Rheos is an upstream Rheos gap, not permission to duplicate it in this repository.
- Fix missing board behavior in `open-hax/eta-mu` / `@eta-mu/rheos`, then consume
  that behavior here.
- Use Rheos CLI, API, MCP, or UI operations for board-state reads, writes,
  comments, and transitions. Do not build a second implementation of those
  operations.
- **Cards may be created and edited manually as Markdown.** This is intended
  Rheos design, not a gap or a workaround: Rheos is built so that a collection of
  Markdown documents is easy to migrate in, so hand-authored cards are first-class
  input. Outcome, scope, non-goals, acceptance criteria, and verification sections
  are written as Markdown and reviewed as diffs. Do not treat this as a second
  write protocol for board state, and do not remove it when the CLI gains more
  authoring verbs — plain Markdown stays a supported entry point.
- A harness without Rheos may inspect board artifacts but must not mutate board
  state or claim board validation.
- `docs/kanban/` contains cards plus the artifacts Rheos itself writes there;
  Rheos decides how its configured `tasksDir` is interpreted.
- Explicit `uuid:` is canonical task identity.
- `epic`, `parent`, and `dependency` use that UUID namespace.
- Omit empty dependency fields.
- Use comma-separated scalar labels for compatibility with the current parser.
- The generated `board.json` is a lossy diagnostic output and is ignored by Git.
- `docs/kanban/.events/ledger.edn` is Rheos's append-only event ledger and **is**
  tracked, so transition, comment, and drift history survives a fresh checkout.
  It is one EDN event per line and union-merged; never rewrite or reorder it.
- Configure lifecycle, transition, and WIP behavior in Rheos/FSM configuration;
  never shadow those rules with repository code. The installed engine resolves
  `"fsm"` as either a known name or a complete FSM map used verbatim — it does
  not merge overlays, and a JSON map cannot express the keyword check ids that
  gates dispatch on. `"fsm": "promethean"` is therefore the only working value
  here; the Clojure build gate is an upstream gap tracked in `receipts.edn`.

## Ledger discipline

1. Append-only: never rewrite historical events or receipts.
2. Preserve provenance: hashes, IDs, selection seeds, model/prompt identity, cache
   keys, and evidence remain inspectable.
3. Preserve epistemic tier: observed -> derived -> provisional -> accepted.
4. Never silently merge based on title, embeddings, edit distance, model agreement,
   or shared vocabulary.
5. Follow Receipt River after substantive repository work, including remote
   connector work.

## Harness boundaries

| Harness | Instructions |
|---|---|
| OpenCode local | `.opencode/AGENTS.md` and `docs/harnesses/opencode-global.md` |
| ChatGPT connectors | `docs/harnesses/chatgpt.md` |
| Perplexity research | `docs/harnesses/perplexity.md` |
| Other | `docs/harnesses/README.md` plus declared capabilities |

Never infer local filesystem, shell, model-server, plugin, audio-device, or write
access from repository references.

## Corpus facts

- Pass 1: 22,208 documents scanned -> 1,522 lyric files -> 690 unique songs.
- Pass 2: 148 same-title clusters covering 413 songs with graded similarity.
- Low-similarity title clusters may be extraction artifacts; similarity never
  becomes identity automatically.
- Pasted artifacts remain in the rendered corpus but are normally excluded from
  thematic discovery.

## Ecosystem relationships

- Epiphany supplies evidence-first archaeology and research/ADR/design discipline.
- Eta-mu/Rheos supplies the Markdown-backed board and is its sole operational
  implementation.
- Muse may supply local compatibility extensions; remote harnesses do not assume
  them.
- Knoxx/OpenPlanner studio work is historical interaction reference, not product
  ownership.
- Suno and other renderers are media/evidence sources, not ontology authorities.

## License

Software and process documentation: GPL-3.0-or-later.
Creative works and lore: CC-BY-SA-4.0 where applicable.
