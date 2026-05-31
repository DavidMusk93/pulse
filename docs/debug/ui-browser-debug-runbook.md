# UI Browser Debug Runbook

This note captures the browser-debugging workflow used to fix the Pulse Run UI layout regressions.

## When to Use

- A UI bug is layout-dependent and screenshots are not enough.
- CSS changes appear correct in code but the deployed browser view still looks broken.
- Monaco/editor, grid/flex, modal, or scroll behavior is involved.
- A polling UI keeps re-rendering and the browser scroll position changes unexpectedly.

## Key Lessons

- Do not rely on static CSS inspection for layout bugs. Measure real DOM boxes in a browser.
- Avoid fixing only overflow symptoms. Find which grid/flex row is stealing space.
- Count direct grid children and explicit grid rows. A missing row can push content into an implicit row with near-zero height.
- Treat `overflow: auto` as a last resort for summary areas. If users must scroll several small boxes to understand status, the layout is not good enough.
- Preserve editor state across polling. Rebuilding Monaco on every refresh resets scroll and feels broken.
- Deploy before final verification when the UI is served from remote coordinator nodes; local jar success is not enough.
- Commit and push immediately after each coherent fix so remote state and git history stay aligned.

## Setup

Keep a port forward to one coordinator:

```bash
ssh -N -L 127.0.0.1:9967:127.0.0.1:9966 fdbd:dc05:11:634::45
```

Start headless Chrome with DevTools Protocol enabled:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new \
  --disable-gpu \
  --remote-debugging-port=9222 \
  '--remote-allow-origins=*' \
  --user-data-dir=/tmp/pulse-chrome-debug \
  --no-first-run \
  --no-default-browser-check \
  about:blank
```

Check DevTools is alive:

```bash
curl -s http://127.0.0.1:9222/json/version
```

## CDP Layout Probe

Use a small Python script with `websocket-client` to drive Chrome. Install once if needed:

```bash
python3 -m pip install --user websocket-client
```

The probe should:

- Open `http://127.0.0.1:9967/hosts`.
- Set a realistic viewport, for example `1024x588`, matching the browser screenshot size.
- Click the first enabled `Run` button or a preferred host tile.
- Wait for `#task-modal.open`.
- Optionally click `#task-run` and wait for JSON output.
- Measure `getBoundingClientRect()` for modal, shell, workspace, execution card, completion card, output area, Monaco editor, and close button.
- Compare output bottom against completion/panel bottom.
- Capture a screenshot for visual confirmation.

Example metrics to collect:

```javascript
(() => {
  const q = s => document.querySelector(s);
  const rect = el => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return {
      x: r.x,
      y: r.y,
      w: r.width,
      h: r.height,
      top: r.top,
      bottom: r.bottom,
      left: r.left,
      right: r.right
    };
  };
  const out = q('#task-output');
  const comp = q('.completion-card');
  const panel = q('.task-panel');
  return {
    viewport: {w: innerWidth, h: innerHeight},
    panel: rect(panel),
    shell: rect(q('.task-shell')),
    workspace: rect(q('.task-workspace')),
    execution: rect(q('.execution-card')),
    completion: rect(comp),
    completionMeta: rect(q('#task-completion-meta')),
    outputTabs: rect(q('.task-output-tabs')),
    output: rect(out),
    monaco: rect(q('#task-output .monaco-editor')),
    closeX: rect(q('#task-close-x')),
    outputClient: out ? {
      clientHeight: out.clientHeight,
      scrollHeight: out.scrollHeight,
      clientWidth: out.clientWidth,
      scrollWidth: out.scrollWidth
    } : null,
    overflow: out && comp ? {
      outputBelowCompletion: out.getBoundingClientRect().bottom > comp.getBoundingClientRect().bottom + 1,
      outputBelowPanel: out.getBoundingClientRect().bottom > panel.getBoundingClientRect().bottom + 1,
      horizontal: out.scrollWidth > out.clientWidth + 1
    } : null
  };
})()
```

## What Broke

The Run UI had multiple regressions:

- The completion card had four direct children: title, metadata, tabs, editor.
- CSS defined only three explicit grid rows.
- The tabs consumed the `1fr` row and the editor fell into an implicit fourth row.
- The editor height became only a few pixels in a realistic viewport.
- The completion metadata and execution card consumed too much vertical space.
- The modal had too much outer chrome, so the actual workspace felt small.
- Re-rendering output during polling rebuilt Monaco and reset the scroll position.

The browser measurements made the failure obvious:

- `#task-output` height was `2px` in one broken state.
- Monaco height was about `5px`.
- `outputBelowCompletion=true` and `outputBelowPanel=true` before the grid fix.
- After later visual changes, no overflow remained but the editor was still too small, so the layout was redesigned as a workbench.

## Fix Patterns

- Define one explicit grid row per direct grid child.
- Keep long, low-priority details out of the main vertical path.
- Replace large detail blocks with compact metrics and a single-line context row.
- Make empty execution queues compact; they should not consume the primary workspace.
- Use a command-center header with actions and status summaries visible at a glance.
- Give the editor the dominant width and a predictable remaining-height row.
- Make modal chrome close to full-screen for operational tooling.
- Keep the close button visible as an absolute overlay so it does not consume layout height.

## Monaco Polling Rules

- If output text is unchanged, do not call `dispose()` or `setValue()`.
- If output changes, save and restore Monaco view state:

```javascript
const viewState = outputEditor.saveViewState();
const scrollTop = outputEditor.getScrollTop();
const scrollLeft = outputEditor.getScrollLeft();
model.setValue(text);
outputEditor.layout();
if (viewState) {
  outputEditor.restoreViewState(viewState);
}
outputEditor.setScrollPosition({scrollTop, scrollLeft});
```

- If formatting JSON asynchronously, save/restore scroll around the format action too.

## Build, Deploy, Verify

Build locally:

```bash
mvn -q test
mvn -q -DskipTests package
shasum -a 256 target/pulse-0.1.0-SNAPSHOT.jar
```

Deploy to the three coordinator nodes:

```bash
PATH="/opt/homebrew/opt/coreutils/libexec/gnubin:$PATH" \
AUTO_OPS_ARTIFACT_ROOT=/Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-debug \
AUTO_OPS_REPORT_DIR=/Users/david/Documents/projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --limit-file /Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-keyed/coordinators.txt \
    --parallel 3 \
    --timeout 240 \
    --max-hosts 3 \
    --yes \
    -- /Users/david/Documents/projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar \
       'fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44' \
       /data24/otf/pulse - cdn_new
```

Verify remote jar and service:

```bash
for h in 'fdbd:dc05:11:634::45' 'fdbd:dc05:13:10c::40' 'fdbd:dc07:0:810::44'; do
  echo "=== $h ==="
  ssh -o ConnectTimeout=10 "$h" \
    'sha256sum /data24/otf/pulse/bin/pulse.jar; systemctl is-active pulse-coordinator.service'
done
```

Re-run the CDP layout probe against the deployed coordinator. A fix is not complete until the measured browser boxes show:

- `outputBelowCompletion=false`
- `outputBelowPanel=false`
- `horizontal=false`
- close button has a visible bounding box
- editor/output has meaningful height in the target viewport
- JSON output keeps scroll position during polling

## Commit Discipline

After every coherent UI fix:

```bash
git add src/main/java/com/bytedance/pulse/HostTilesPage.java
git commit -m "Describe the UI fix"
git push
```

Do not wait to batch multiple unrelated UI attempts. For remote UI debugging, the deployed jar SHA, browser measurements, and git commit should be traceable to each other.
