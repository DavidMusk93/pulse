# Frontend Build Environment Notes

## Node/npm unavailable in current agent shell

- The current macOS agent shell cannot find `node` or `npm` on `PATH`.
- `src/main/frontend/package.json` builds static assets with `npm run build`, which invokes `vite build`.
- Without Node/npm, edits in `src/main/frontend/src` cannot be reliably compiled into `src/main/resources/static/pulse-hosts.js` and `pulse-hosts.css`.

## Why this matters

- The coordinator serves static assets from `src/main/resources/static`, so production UI changes must update those files.
- Hand-editing minified static bundles is error-prone and can drift from React source.
- Any frontend work should either restore the Node/npm toolchain first or use a small, reviewed sync script with tests that assert the generated static bundle contains the expected UI markers.

## Recommended workflow

- Prefer restoring Node/npm and running `npm run build` from `src/main/frontend`.
- If the toolchain is unavailable, keep any temporary static sync script under `.tmp/` and do not stage it.
- Add or update HTTP/static asset tests before changing static assets, so stale bundles fail fast.
- Record any manual static sync in `docs/report/coordinator-local-storage-timeseries-implementation-progress.md`.
