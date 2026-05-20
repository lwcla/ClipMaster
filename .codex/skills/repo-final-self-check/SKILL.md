---
name: repo-final-self-check
description: Final response self-check for repository code changes. Use before finalizing any code edit, refactor, bug fix, UI change, data-layer change, docs/rules update, backup/restore change, or commit-related task to ensure the final answer reports docs, reuse, component/class/method changes, rule exceptions, and validation.
---

# Repo Final Self Check

Use this skill immediately before the final answer after code or rule/document changes.

## Check Before Final

1. Run `git diff --check` unless there is a clear reason not to.
2. Run the closest compile/test command for Kotlin/Compose/Android changes. If not run, state why.
3. Confirm whether docs needed updating and whether they were updated.
4. Confirm reuse search results:
   - reused existing ability, or
   - did not reuse because of responsibility, dependency direction, unstable contract, or risk.
5. Confirm component/class/method changes:
   - added/split/moved components, classes, methods, mapper, formatter, validator, utility, DAO, state model,
   - or no such changes.
6. Confirm rule exceptions:
   - temporary no split,
   - no tests,
   - no logs,
   - no docs,
   - no R8/release validation,
   - backup coverage not updated,
   - any other tracked exception.
7. Confirm privacy/logging risks are not introduced.
8. Confirm no unrelated dirty user changes were reverted.

## Final Answer Shape

Keep it concise, but include:

- **方案文档**: updated doc or why not needed.
- **复用检查**: reused or reason not reused.
- **组件/类/方法**: what changed and any split decisions.
- **规则例外**: none, or list tracked exceptions.
- **验证结果**: commands and result, or not run with reason.

For tiny non-code documentation-only changes, compress the same five items into one short paragraph.
