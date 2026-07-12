# Asset capture scripts (slides 28 + 44)

Playwright capture scripts for the waypoint-graph and session-report talk assets.
The full playbook — prereqs, trailblaze commands, daemon handling, gotchas — is
[../public/asset-recipe-regenerate.txt](../public/asset-recipe-regenerate.txt).

- `capture-graph-stills.mjs` — asset-c-graph-normal.png + asset-c-graph-subway.png
- `capture-graph-video.mjs` — asset-c-graph.mp4 (webm → ffmpeg)
- `capture-report-tabs.mjs` — asset-a2-report-1.png, -3.png, -extra-grid.png
- `capture-trailrunner-hier.mjs` — asset-a2-report-2.png (hierarchy overlay)

All need `playwright-core` (see recipe §0 for the private-registry `.npmrc` gotcha) and
system Chrome; run with `bun`.
