# Generated report assets

The [Report Gallery](../../reports.md) and the [landing page](../../index.md) embed embed
per-trail report assets that live under `<trail>/` here:

| File | Produced by | Notes |
|---|---|---|
| `storyboard.webp` | `trailblaze report --storyboard` | single-frame grid of every step |
| `timeline.webp`   | `trailblaze report --webp`       | animated walkthrough |
| `report.html`     | `trailblaze report`              | full self-contained interactive report (~MBs) |

**Nothing per-trail is committed.** The `<trail>/` directories are gitignored. The only
image in the repo is the single, reusable generic placeholder at
`docs/images/report-pending.webp`.

Where the real assets come from:

1. A trail's CI job (e.g. `wikipedia-trails`) runs `trailblaze report --storyboard --webp`
   and uploads a `docs-report-<trail>` **artifact** (transient Actions storage).
2. The `github-pages` workflow downloads the latest successful run's artifact into
   `<trail>/` in the build workspace before `mkdocs build`.
3. `mkdocs build` copies them into the published GitHub Pages site.

For any asset still missing at build time (no successful run yet, expired artifact, local
`mkdocs serve`, or the PR `docs-build-check`), `docs_hooks.py` fills it with the generic
placeholder so `mkdocs build --strict` stays green. Real fetched assets always win.

To preview real assets locally, generate them with `trailblaze report --output-dir
docs/generated/reports/<trail> --storyboard --webp` before `mkdocs serve`.
