# Auto Ops Argument Passing Notes

## Same-line environment assignment does not feed shell expansion

- Shell assignments that prefix a command, for example `FOO=value command "$FOO"`, set `FOO` only in the command environment.
- The shell expands `"$FOO"` before starting `command`, so it can expand to the old value or an empty string.
- This can silently drop positional arguments passed after `--` to `scripts/call.sh`.

## Deployment impact

- `docs/script/pulse-cdn-new-deploy.sh` expects stable positional arguments after the auto-ops host and index:
  - `$3`: local JAR path
  - `$4`: coordinator CSV
  - `$5`: install root
  - `$6`: optional JRE tarball marker
- If the coordinator CSV expands incorrectly, downstream argument shifting can surface as remote `set -u` failures such as `$6: unbound variable`.

## Safer pattern

Use a prior assignment statement or inline the literal value:

```bash
COORDINATORS='fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44'
AUTO_OPS_ARTIFACT_ROOT=/path/to/.tmp/auto-ops \
AUTO_OPS_REPORT_DIR=/path/to/docs/report \
  bash scripts/call.sh \
    -f /path/to/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --yes \
    -- /path/to/pulse.jar "$COORDINATORS" /data24/otf/pulse -
```

Or avoid expansion entirely:

```bash
bash scripts/call.sh ... -- /path/to/pulse.jar \
  'fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44' \
  /data24/otf/pulse -
```
