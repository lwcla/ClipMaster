---
name: repo-backup-coverage
description: Backup and restore coverage workflow for this repository. Use when adding or changing Room tables, DAO fields, settings, user-generated data, download/cache metadata, cross-install state, WebDAV/local backup behavior, restore reports, dirty marking, retention, Auto Backup exclusions, or anything that may need backup/restore support.
---

# Repo Backup Coverage

Use this skill with `$repo-coding-gate` whenever a change may affect data that should survive uninstall/reinstall or restore.

## Coverage Decision

1. Open or update `docs/webdav_backup_plan.md`.
2. Classify new/changed data:
   - user-generated data,
   - cache with cross-install meaning,
   - settings/user preference,
   - download or media metadata,
   - temporary/device-bound/sensitive state,
   - derived/recomputable data.
3. Decide whether to include it in backup:
   - Include if it is user-created, user-visible, costly to recreate, or meaningful after reinstall.
   - Exclude if it is credential, token, cookie, local permission URI, temp path, pending output URI, health state, retry count, running task state, or safely recomputable.
4. Document the decision and reason.

## If Included

Update all relevant layers:

- backup protocol/model/manifest summary,
- export mapper and field whitelist,
- restore mapper,
- stable dedupe key and conflict rules,
- preview counts,
- restore report category/counts,
- dirty marking trigger,
- retention/list behavior if applicable,
- system Auto Backup exclusion if sensitive or device-bound,
- tests or validation plan.

Restore must be idempotent: repeating the same backup should not duplicate data or incorrectly report new/updated rows.

## If Excluded

Document:

- why it is excluded,
- user impact after reinstall,
- whether future work may include it.

## Privacy

Never back up WebDAV password, cookies, tokens, auth state, local SAF URI, temp paths, pending output URI, complete URL query values, or media file bodies unless a future explicit media-backup feature says so.
