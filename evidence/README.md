# Classifier warning verification evidence

These files preserve the actual 2026-09-12 runs referenced by the append-only receipt in PR18. The JSON records are unchanged: their absolute cwd/log paths describe the original sandbox run, not paths a new checkout must provide. The lint/test output copies retain the recorded bytes. The dry-run output file contains a clearly marked extraction of actual counts and selected repository IDs; rendered lyric prompts and workstation source paths are omitted.

| Run record | Durable output |
|---|---|
| [Lint](calliope-main-warning-lint.json) | [Output](logs/calliope-main-warning-lint.txt) |
| [Tests](calliope-main-warning-test.json) | [Output](logs/calliope-main-warning-test.txt) |
| [Seeded dry run](calliope-main-warning-dry-run.json) | [Output](logs/calliope-main-warning-dry-run.txt) |

The dry run renders the classifier request locally. It does not call a model. The dry-run command can reconstruct the rendered request from the existing corpus. JVM trust-store path/options describe the isolated launcher, not production credentials.

Original dry-run output SHA-256: `15fedd11ec093877808938b5958ba04e169d977c551a0a33e5d239a92fc409d1`. The compact extraction is not a claim to preserve the raw prompt bytes.
