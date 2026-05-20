---
name: repo-architecture-boundaries
description: Architecture-boundary workflow for repository code. Use when modifying or adding ViewModels, Repositories, Workers, DAOs, Room entities, mappers, comparators, parsers, formatters, validators, helpers, platform wrappers, serialization models, settings, or utility functions where responsibilities, placement, naming, failure contracts, and module boundaries matter.
---

# Repo Architecture Boundaries

Use this skill with `$repo-coding-gate` for non-trivial Kotlin/domain/data-layer work.

## Boundary Check

1. Identify the entry class and keep it orchestration-focused:
   - ViewModel connects UI state/events,
   - Repository coordinates data sources and transactions,
   - Worker coordinates background execution,
   - DAO only exposes database contracts,
   - mapper/parser/formatter/validator classes hold pure or focused logic.
2. Search for existing helpers, mappers, validators, formatters, repositories, and platform wrappers before adding new code.
3. Place new logic where maintainers would look by domain plus responsibility, not in generic `Utils` or at the bottom of an overloaded entry file.
4. Prefer pure Kotlin functions/objects for stateless parsing, comparison, mapping, normalization, and validation.
5. Use constructor-injected classes for logic needing `Context`, database, network, dispatchers, system APIs, or replaceable dependencies.

## Method And Class Rules

- Name by responsibility: `*Mapper`, `*Comparator`, `*Parser`, `*Formatter`, `*Validator`, `*Reader`, `*Writer`, `*Publisher`, `*Scheduler`, `*Repository`.
- Define failure semantics clearly: return value, sealed result, nullable, or exception. Do not mix silently.
- Avoid business mapper/comparator/parser logic inside large ViewModel/Repository files unless it is a documented temporary exception.
- Keep module dependencies one-way; do not make lower modules depend on app feature code for reuse.
- Add Chinese comments for types, methods, fields, contracts, and non-obvious branches.
- If adding serialization or external protocol models, use stable field annotations and evaluate keep/R8 needs.

## Refactor Safety

- Split low-risk pure logic first, then IO/platform wrappers, then reusable UI/state, then core orchestration.
- Keep behavior equivalent during extraction unless the user explicitly asked for behavior change.
- Document any temporary non-split logic in the plan or final answer.
