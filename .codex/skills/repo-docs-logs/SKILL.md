---
name: repo-docs-logs
description: Documentation and logging workflow for repository changes. Use when changing behavior, architecture, UI flow, data contracts, lifecycle, background work, network, file IO, database writes, backup/restore, diagnostics, logging, errors, rules, or any user-visible behavior that should update docs or log plans.
---

# Repo Docs Logs

Use this skill with `$repo-coding-gate` whenever code changes affect behavior, design, data flow, diagnostics, or rules.

## Documentation Workflow

1. Find the existing plan document in `docs/` before creating a new one.
2. Update the existing document when the change affects:
   - behavior or UX,
   - architecture or responsibilities,
   - data contracts or schema,
   - backup/restore coverage,
   - lifecycle/performance,
   - logging/diagnostics,
   - rules or conventions.
3. Keep one shared master document for shared components/capabilities; page docs should only summarize page-specific integration.
4. Every plan document must have a status and a change record.
5. If implementation differs from the plan, update the document to describe the final behavior and tradeoff.
6. If docs are not needed, state why in the final answer.

## Logging Workflow

For network, file IO, database writes, background tasks, permissions, system APIs, long tasks, retry, cache, parse, migration, restore, backup, or error branches:

1. Add or update a "日志与诊断计划" section before or with implementation.
2. Specify log level, trigger, fields, `taskId`/`traceId` propagation, reasonCode, retry/skipped semantics, and cleanup boundaries.
3. Use simplified Chinese in log text; keep stable field names, TAG, enum values, and reasonCode in English if already stable.
4. Never log clipboard content, search query full text, cookies, tokens, passwords, complete URL query strings, local authorization URI, WebDAV endpoint/password, request/response bodies, full JSON/HTML, or backup contents.
5. Prefer low-sensitive structured fields: counts, booleans, status codes, duration, file size, sanitized file name, retryable, reasonCode.
6. Rate-limit or avoid high-frequency logs.

## Final Answer Requirements

Report which docs changed, what log plan changed, and whether any logging was intentionally omitted.
