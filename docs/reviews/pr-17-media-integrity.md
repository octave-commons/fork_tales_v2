# PR 17 media integrity review

Reviewed parent: `ba72dd3be4c85d91b2d0250698eff52b21a77af9`.
Date: 2026-09-11. Harness: ChatGPT Work sandbox with the GitHub connector.

All five CodeRabbit findings reproduce in the original implementation.
The subsequent Codex review of `99990d7` found that historical receipts need
their SHA-8 prefixes compared as well; all 1,909 committed track events use
that legacy field, and none carries a full SHA-256.

| Finding | Correction | Regression evidence |
|---|---|---|
| Sync can upload missing or altered bytes | Hash-verify the manifest before invoking rclone | Real Babashka CLI tests use a recording rclone; missing, resized, same-size altered, and malformed datasets exit unsuccessfully without invoking it |
| Assembly retains deleted songbook files | Remove obsolete supported text copies after copying current sources | Delete Markdown and text sources between assembly runs; verify remaining contents and regenerated manifest |
| Reader accepts invalid manifests | Validate closed envelope and entries, dataset identity, count, total bytes, unique paths, and exactly one EDN form per line | Missing fields, invalid hashes and sizes, wrong identity, empty entries, duplicate paths, and trailing forms are rejected |
| Manifest paths escape the root | Reject traversal and non-POSIX paths; compare canonical paths by path components before reading or hashing | Absolute, dot, parent, Windows-style, and sibling-prefix symlink escape tests |
| Ledger verification ignores full hashes | Report `:hash-drift` and make CLI ledger verification fail | Contradictory receipts fail on JVM and CLI; full-hash differences are detected even when the SHA-8 prefix matches |
| Historical SHA-8 receipts bypass hash checks | Compare full SHA-256 when present, otherwise the recorded SHA-8 prefix | JVM and real CLI regressions cover both formats and full-hash precedence; the committed manifest/ledger comparison has zero drift |
| Assembly follows an in-root destination symlink | Reject every symlink component beneath the selected root before copying | JVM tests cover file and directory links; the real Babashka CLI rejects a songbook path linked to an unrelated MP3 without altering it |
| Ingestion trusts an existing destination | Verify copied or existing bytes before returning discovery receipt data | Actual `tracks!` execution accepts intact existing files, fails on truncation or same-size corruption, and appends no discovery event for the conflict |
| Manifest paths ignore content-addressed names | Require MP3/JPEG/JSON basenames to equal the SHA-256 prefix; text retains readable filenames | Reader and Malli reject mismatched/friendly media basenames; regeneration after corruption fails and preserves the previous manifest |
| Ingestion follows slug-directory links | Reuse assembly's component-by-component write guard through `store-asset!` | Actual Babashka track ingestion rejects directory and file aliases, preserving unrelated files and the historical ledger prefix |
| Later receipts conceal historical contradictions | Compare every receipt, preserving event IDs in sorted drift diagnostics | Both receipt orders produce the same failures; repeated matching receipts remain valid; the real CLI rejects earlier contradictions |
| Manifest generation follows aliases | Guard the manifest destination and replace it from a temporary file | JVM and CLI symlink tests preserve outside files; replacing a hard-linked manifest preserves the other link's contents |
| Readable text names escape the songbook namespace | Restrict the readable-name exemption to `text/`; elsewhere require a matching content address | Runtime/Malli reject root and other-directory text names; a stray README causes generation to fail without replacing the manifest |
| External ingestion leaves git metadata stale | Mirror verified JSON and the full manifest into repository `tracks/` before recording run completion | Real Babashka ingestion populates both roots, keeps large media external, and refuses symlinked projection targets; JVM tests reject corrupt JSON and preserve hard-link targets |
| Mirroring retains retired JSON metadata | Remove target JSON paths omitted from the source manifest before publishing that manifest | A two-run JVM regression retires old metadata and introduces a new address; real Babashka ingestion removes obsolete nested metadata |
| File replacement does not request atomicity | Require `ATOMIC_MOVE` for manifests and mirrored JSON; unsupported providers fail without fallback | Existing generation, hard-link preservation, and real Babashka ingestion regressions pass with atomic replacement |
| Atomic replacements make shared files owner-only | Preserve existing POSIX permissions; new targets use ordinary readable creation modes subject to umask | JVM tests preserve shared modes across manifest regeneration and JSON mirroring, including copies from private sources |
| Manifest generation time accepts arbitrary strings | Share an ISO-8601 instant predicate between runtime and Malli | Valid offsets and fractional seconds pass; invalid dates, times, empty strings and missing zones fail; real sync rejects malformed provenance |
| Missing or mistyped commands exit successfully | Default CLI dispatch exits nonzero; explicit help remains successful | Real Babashka tests cover no command, a typo, and help without invoking rclone |

The subsequent retirement-policy suggestion (`discussion_r3994218793`) was
adjudicated without changing the verifier. Metadata mirroring reconciles a
derived copy; it does not retire source assets or discovery receipts. Missing
historical assets remain reportable under ADR-002. A direct Babashka reproduction
reported a removed JSON path and cleared it after restoring the original bytes,
without changing any receipt. An explicit retirement lifecycle needs its own
accepted contract; absence alone must not silently make history inactive.

Projection writes are atomic per file, not a transaction across the directory.
A failed run can leave partial local projection changes and must be inspected or
retried before committing; ingestion does not append a run-completed event.

The runtime and Malli law share dependency-free path and hash predicates.
Reader validation stays available to Babashka without requiring Malli. Scanner
and law accept the same case-insensitive extensions. Manifest generation refuses
invalid/empty output. Directory walks do not follow symlink cycles, and assembly
rejects all symlinked write destinations and source/destination overlap.

## Verification

- `clojure -M:test`: 110 tests, 612 assertions, zero failures and errors.
- `clj-kondo --lint src/calliope/media src/calliope/law/media.cljc test/calliope/media test/calliope/law/media_test.clj test/calliope/test_runner.clj scripts/media.clj`: zero errors and warnings.
- `clj-kondo --lint scripts/corpus.clj`: zero errors and warnings. Scripts are
  linted separately because they deliberately share the standalone `user` namespace.
- JVM AOT compilation succeeded for `calliope.media.manifest`,
  `calliope.media.dataset`, and `calliope.law.media`.
- `bb scripts/media.clj where` read the committed manifest: 2,598 entries,
  4,440,988,097 declared bytes. This is metadata validation, not verification of
  absent media bytes.
- `clojure -M:classify -- --seed 3721599729 --dry-run`: exit 0.
- The committed manifest agrees with all 1,909 historical track receipts:
  zero untracked entries, missing entries, byte drift, or hash-prefix drift.
  This compares recorded metadata; it does not assert the external bytes are present.
- Replaying the initial dataset/CLI regressions used for `99990d7` against the original implementation
  produced 38 failures and zero errors across 12 tests and 75 assertions.
- Repository Contracts installs Babashka for the real CLI tests and now triggers
  when either media/corpus script or `bb.edn` changes.

The recording rclone proves admission and exit behavior only. No live remote
synchronization, full-corpus byte validation, model invocation, or audio playback
was performed. Remote deletion remains deliberate under ADR-002; removing a
stale local text projection does not delete historical remote files.

The append-only receipt is in `receipts.edn`. Review completion and merge are
conditional on GitHub evidence for the final commit; this document does not
claim either happened.
