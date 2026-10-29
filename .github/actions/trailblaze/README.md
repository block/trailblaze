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
    uses: block/trailblaze/.github/workflows/trail-runner.yml@<release-tag>
    with:
      request: .github/trailblaze-runs.yaml
      app-artifact: app
```

`<release-tag>` is a [Trailblaze release](https://github.com/block/trailblaze/releases) such as
`v2026.10.13`, which pins both the workflow and the CLI. A commit hash works too, with `version:`
naming the CLI release to run.

This directory is the composite action that workflow calls for each job. To run one device in a
job you already have, use it directly; its inputs are in [`action.yml`](action.yml).
