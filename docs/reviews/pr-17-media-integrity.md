# PR 17 media integrity review

Reviewed parent: `ba72dd3be4c85d91b2d0250698eff52b21a77af9`.
Date: 2026-09-11. Harness: ChatGPT Work sandbox with the GitHub connector.

All five CodeRabbit findings reproduce in the original implementation.

| Finding | Correction | Regression evidence |
|---|---|---|
| Sync can upload missing or altered bytes | Hash-verify the manifest before invoking rclone | Real Babashka CLI tests use a recording rclone; missing, resized, same-size altered, and malformed datasets exit unsuccessfully without invoking it |
| Assembly retains deleted songbook files | Remove obsolete supported text copies after copying current sources | Delete Markdown and text sources between assembly runs; verify remaining contents and regenerated manifest |
| Reader accepts invalid manifests | Validate closed envelope and entries, dataset identity, count, total bytes, unique paths, and exactly one EDN form per line | Missing fields, invalid hashes and sizes, wrong identity, empty entries, duplicate paths, and trailing forms are rejected |
| Manifest paths escape the root | Reject traversal and non-POSIX paths; compare canonical paths by path components before reading or hashing | Absolute, dot, parent, Windows-style, and sibling-prefix symlink escape tests |
| Ledger verification ignores full hashes | Report `:hash-drift` and make CLI ledger verification fail | Regenerate the manifest after a same-size content change; current bytes pass manifest verification but fail the historical hash check |

The runtime and Malli law share dependency-free path and hash predicates.
Reader validation stays available to Babashka without requiring Malli. Scanner
and law accept the same case-insensitive extensions. Manifest generation refuses
invalid/empty output. Directory walks do not follow symlink cycles, and assembly
rejects destination links that would overwrite source lyrics.

## Verification

- `clojure -M:test`: 96 tests, 433 assertions, zero failures and errors.
- `clj-kondo --lint src/calliope/media src/calliope/law/media.cljc test/calliope/media test/calliope/law/media_test.clj test/calliope/test_runner.clj scripts/media.clj`: zero errors and warnings.
- JVM AOT compilation succeeded for `calliope.media.manifest`,
  `calliope.media.dataset`, and `calliope.law.media`.
- `bb scripts/media.clj where` read the committed manifest: 2,598 entries,
  4,440,988,097 declared bytes. This is metadata validation, not verification of
  absent media bytes.
- `clojure -M:classify -- --seed 3721599729 --dry-run`: exit 0.
- Replaying the new dataset/CLI regressions against the original implementation
  produced 38 failures and zero errors across 12 tests and 75 assertions.
- Repository Contracts installs Babashka for the real CLI tests and now triggers
  when `scripts/media.clj` or `bb.edn` changes.

The recording rclone proves admission and exit behavior only. No live remote
synchronization, full-corpus byte validation, model invocation, or audio playback
was performed. Remote deletion remains deliberate under ADR-002; removing a
stale local text projection does not delete historical remote files.

The append-only receipt is in `receipts.edn`. Review completion and merge are
conditional on GitHub evidence for the final commit; this document does not
claim either happened.
