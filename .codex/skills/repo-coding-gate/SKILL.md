---
name: repo-coding-gate
description: Repository coding gate for LwlDemo-style work. Use before any code change, bug fix, refactor, UI change, data-layer change, Android/Kotlin/Compose edit, or implementation plan where Codex must obey hard repository rules, run reuse checks, update docs, preserve architecture boundaries, validate changes, and produce a final self-check.
---

# Repo Coding Gate

Use this skill as the first workflow before changing code. It does not replace `AGENTS.md`; it turns the repository hard rules into an execution sequence.

## Gate Sequence

1. Announce the gate in one short user-facing update:
   - what existing capability you will search for,
   - which docs may need updating,
   - how you expect to split components/classes/methods,
   - what validation you expect to run.
2. Read the local `AGENTS.md` rules that apply to the task if not already in context.
3. Search before editing:
   - current feature/package,
   - current module,
   - shared modules,
   - full repo only as fallback.
   Use `rg` or `rg --files`; record reuse or non-reuse in the final answer.
4. Pick companion skills when relevant:
   - Use `$repo-compose-discipline` for Compose/UI work.
   - Use `$repo-architecture-boundaries` for ViewModel, Repository, DAO, mapper, formatter, parser, validator, helper, utility, or data-flow work.
   - Use `$repo-docs-logs` for behavior, architecture, logging, diagnostics, lifecycle, or user-visible flow changes.
   - Use `$repo-backup-coverage` for Room tables, settings, user data, downloads/cache metadata, backup, restore, dirty state, or Auto Backup impact.
   - Use `$repo-git-discipline` for staging, committing, rewriting commit messages, checking commit scope, or any git operation.
5. Update docs before or together with code when behavior, UI, architecture, data contracts, logging, backup, lifecycle, or rules change.
6. Implement with narrow scope. Keep entry classes as orchestration; move reusable logic to named collaborators.
7. Validate with the closest useful command. For Kotlin/Compose/Android, prefer module compile commands and `git diff --check`.
8. Before finalizing, use `$repo-final-self-check`.

## Blockers

Do not edit code until you have resolved or documented:

- no reuse search was performed,
- required docs are stale or missing,
- new logic would enlarge an already overloaded file without a tracked exception,
- logging/privacy impact is unclear,
- validation is impossible and the risk is not stated.
