---
name: repo-git-discipline
description: Git workflow discipline for this repository. Use when staging files, creating commits, rewriting commit messages, checking commit scope, preparing commit bodies, reviewing git status, pushing, or handling any git operation so Codex uses simplified Chinese commit messages, preserves user changes, avoids destructive history operations, validates scope, and reports results.
---

# Repo Git Discipline

Use this skill with `$repo-coding-gate` when the user asks to generate a commit, stage files, adjust a commit message, inspect commit scope, or perform any git operation.

## Before Staging

1. Run `git status --short`.
2. Inspect the relevant diff before staging:
   - use `git diff -- <path>` for unstaged files,
   - use `git diff --staged` for staged files.
3. Stage only files related to the current task. Do not use `git add .` unless the whole dirty worktree has been confirmed as part of the requested commit.
4. Preserve unrelated user changes. Never revert, discard, or overwrite unrelated dirty files.
5. Keep commit scope single-purpose. Separate unrelated rule changes, refactors, formatting, and feature changes unless they are part of one behavior change.

## Commit Message Rules

- Use simplified Chinese for title and body.
- Title should be a short verb-object phrase describing the real scope and purpose.
- Use a scoped title when helpful, for example `备份：修复恢复报告统计`.
- Do not create a commit with title only.
- Body must include:
  - key changes,
  - validation commands and results,
  - risks, tradeoffs, unverified items, or follow-up notes when applicable.
- If a commit contains both rule docs and code, explain whether the rule change drove the code change.

## Validation Before Commit

- For Kotlin/Compose/Android changes, run the closest compile/test command, commonly `./gradlew :app:compileDebugKotlin` or a more specific module command.
- Always run `git diff --check` unless clearly unnecessary or impossible.
- If validation cannot run, state why in the commit body or final answer.

## Forbidden Without Explicit User Approval

Do not run:

- `git reset --hard`
- `git checkout --`
- `git rebase`
- `git commit --amend`
- force push
- any command that discards user work or rewrites history

If such an operation is truly needed, ask the user first.

## After Commit

1. Run `git status --short`.
2. Run `git log -1 --oneline --stat`.
3. Report the commit hash, title, files included, validation run, and any remaining dirty files.
