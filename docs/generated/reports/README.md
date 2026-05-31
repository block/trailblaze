# Generated report assets

These files back the [Report Gallery](../../reports.md) page and the embed on the
[landing page](../../index.md). **They are committed as labeled placeholders** so the
MkDocs `--strict` build always succeeds (a missing asset link aborts strict mode).

On each push to `main`, the GitHub Pages workflow downloads the latest successful trail
run's report artifacts and **overwrites these files in the build workspace** before
`mkdocs build` — the deployed site shows the real, fresh reports. The overwrite is not
committed back, so the repo stays small and git history doesn't churn with multi-MB
HTML.

Per trail (e.g. `wikipedia/`):

| File | Produced by | Notes |
|---|---|---|
| `storyboard.webp` | `trailblaze report --storyboard` | single-frame grid of every step |
| `timeline.webp`   | `trailblaze report --webp`       | animated walkthrough |
| `report.html`     | `trailblaze report`              | full self-contained interactive report (~MBs) |

Do not hand-edit these — regenerate via `trailblaze report` if you need to refresh the
committed baseline locally.
