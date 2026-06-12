---
name: "tmp-workspace"
description: "Guides safe temporary workspace usage. Invoke when creating temporary scripts, logs, debug artifacts, experiment data, or disposable files."
---

# Temporary Workspace

Use this skill whenever a task needs temporary files, scripts, logs, downloaded artifacts, experiment outputs, or intermediate debug notes.

## Core Rules

- Create temporary files under `.tmp/` at the project root.
- Ensure `.tmp/` is ignored by git before writing temporary artifacts.
- Put temporary logs under `.tmp/logs/`.
- Put temporary scripts under `.tmp/scripts/`.
- Put temporary data under `.tmp/data/`.
- Put temporary reports under `.tmp/reports/`.
- Name temporary artifacts by purpose, such as `.tmp/bench-20260530.log`.
- Do not stage or commit `.tmp/`.
- Do not scatter temporary debug files in the project root.
- Do not delete temporary artifacts just to hide working state; preserve evidence unless cleanup is explicitly requested.

## Promotion Rules

- Promote reusable runtime evidence and root-cause analysis to `docs/debug/`.
- Promote user-facing investigation reports to `docs/report/`.
- Promote reusable operational scripts to `docs/script/` or the project script directory.
- Promote temporary analysis conclusions with reusable value to `docs/` or `designs/`.
- Promote temporary commands that affect reproducible experiment results into the relevant document.
- Consider promoting a temporary script after it is reused more than twice.
- Keep one-off artifacts in `.tmp/` unless the user requests cleanup.

## Cleanup Rules

- Before committing, run `git status` and confirm no `.tmp/` files are staged.
- If a temporary file has reusable value, archive it in the appropriate `docs/` directory.
- If a temporary file has no reusable value and the user asked for cleanup, delete it with the file deletion tool.
- If debug notes exist in the project root, classify them before commit: archive valuable notes under `docs/debug/` or delete valueless notes only when cleanup is requested.

## Recommended Structure

```text
.tmp/
  logs/
  scripts/
  data/
  reports/
```

## Workflow

1. Decide whether the task needs temporary artifacts.
2. Confirm `.tmp/` exists or create it.
3. Confirm `.tmp/` is ignored by git.
4. Write temporary scripts, logs, data, or reports under `.tmp/`.
5. Explain relevant temporary paths in the final result when useful.
6. Before commit, verify `.tmp/` is not staged.
7. Promote reusable artifacts to `docs/` before committing.
