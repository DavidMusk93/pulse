# Shell Heredoc And Inline Script Notes

## Rule

- Do not use heredoc or long inline scripts for deployment, SSH, curl verification, or JSON parsing in this agent terminal.
- Put non-trivial scripts under `.tmp/`, review the file, then execute the file directly.
- Keep `.tmp/` scripts untracked unless the user explicitly asks to keep them as product code.

## Why

- The terminal renderer can interleave prompts such as `heredoc>` into captured output.
- Quoting can be altered or hard to inspect when commands contain nested SSH, curl URLs, JSON, or shell variables.
- Bad evidence is worse than no evidence: exit code `0` with polluted output can hide that the intended command did not run.

## Required workflow

- For Python verification, create `.tmp/<name>.py` and run `python3 .tmp/<name>.py`.
- For remote shell verification, create `.tmp/<name>.sh` and run `bash .tmp/<name>.sh`.
- For auto-ops deploy commands, avoid same-line environment assignment when the value is also passed as an argument.
- Always inspect clean stdout for semantic evidence, not only process exit code.
- Keep Python verification print logic simple; compute booleans before formatting rather than embedding escaped strings inside f-string expressions.
- Local JSON summarization is still verification. Do not use `python3 - <<'PY'` for quick summaries; create `.tmp/summarize_*.py` and execute it.

## Regression note

- 2026-06-08: a local SQLite JSON summary was briefly run with heredoc. The output was readable but still polluted by `heredoc>` prompts, so the correct replacement is `.tmp/summarize_heartbeat_sqlite_analysis.py`.

## Anti-patterns

```bash
python3 - <<'PY'
...
PY
```

```bash
ssh host 'bash -s' <<'REMOTE'
...
REMOTE
```

```bash
FOO=value command "$FOO"
```
