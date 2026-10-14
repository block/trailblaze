# Run Trailblaze trails on GitHub Actions

**Docs: [Run Trails on GitHub Actions](https://block.github.io/trailblaze/github-actions/)**
([source](../../../docs/github-actions.md)). The request format, fields, sharding, publish modes and
versioning are documented there, not here.

List the devices and trails in one request file, and the
[reusable workflow](../../workflows/trail-runner.yml) runs every trail on every device in parallel,
then reports once in the job summary, a PR comment and an HTML report.

```yaml
jobs:
  trails:
    permissions: { contents: read, pull-requests: write }
    uses: block/trailblaze/.github/workflows/trail-runner.yml@v2026.10.13
    with:
      request: .github/trailblaze-runs.yaml
      app-artifact: app
```

This directory is the composite action that workflow calls for each job. To run one device in a
job you already have, use it directly; its inputs are in [`action.yml`](action.yml).
