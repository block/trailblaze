---
title: "`all:` — the universal root classifier"
type: decision
date: 2026-08-06
---

# `all:` — the universal root classifier

Every classifier lineage now ends at a single universal root, `all`. An entry
keyed `all:` in any classifier-keyed map — step `recordings:`, the trailhead's
recordings, waypoint blocks, `config.devices:` driver pins, `config.skip:` —
resolves for **every** device, at the **lowest** priority.

## Background

The [classifier lineage](2026-06-28-classifier-lineage-primitive.md) resolves
classifier-keyed maps closest-wins: walk the device's chain from most specific
(`ios-iphone`) up to its family root (`ios`) and take the first declared entry.
Each chain ended at its **platform family**, so there was no key that reached
every device.

That forces cross-platform trails whose platforms genuinely share an entry to
declare it once per platform. The trailhead is where this bites hardest: it is
one tool call per device, and a target whose trailhead tool is itself
cross-platform (`supportedPlatforms: [android, ios]`, same args on both) still
needs byte-identical `android:` and `ios:` blocks in every trail — a copy that
can silently drift.

```yaml
trailhead:
  step: Launch signed in on the target screen
  recording:
    android:
      app_launchSignedIn: { route: /settings }
    ios:
      app_launchSignedIn: { route: /settings }   # byte-identical duplicate
```

## What we decided

### `all` is the implicit ancestor of every classifier

`TrailblazeClassifierLineage` appends `all` as the final entry of every
non-empty chain, in both `chainFor` (single classifier) and `resolutionChain`
(a device's broad-first segments):

```
android-phone   →  android  →  all
ios-iphone      →  ios      →  all
[ios, iphone]   →  ios-iphone, ios, iphone, all
```

The duplicated trailhead above becomes one block:

```yaml
trailhead:
  step: Launch signed in on the target screen
  recording:
    all:
      app_launchSignedIn: { route: /settings }
```

### A default, not a straitjacket

`all` sits **strictly last** on every chain — after the compound identity, its
ancestors, and every bare-segment fallback. Any explicitly-declared classifier
outranks it, so a platform that genuinely diverges keys its own entry and wins
on that platform while the others keep the shared one:

```yaml
recordings:
  all: [...]          # what the platforms share
  ios: [...]          # iOS diverges; wins on iOS only
```

In `resolutionChain` the append happens once at the end of the merged chain —
not inside each per-segment expansion — so `all` can never ride the compound
identity's lineage in ahead of a lower-priority segment fallback (an
`iphone:`-keyed entry still beats `all:`).

### The vocabulary was already reserved

`all` is already the format's "every one of them" meta-key: a target manifest's
`drivers:` list accepts `all` for every driver type (`DriverTypeKey`). This
change gives the same word the same meaning in the classifier namespace.
`registerParentOverride` now rejects `all` as a child — the universal root
cannot be given a parent.

### One consumer adjusted: filename→platform backfill

`TrailIndexBuilder.platformFromFileName` derived a legacy recording's platform
by taking the **last** entry of the filename stem's chain and checking it
against the platform roots. The last entry is now always `all`, so it takes the
first platform-rooted ancestor on the chain instead — same result for every
real stem, robust to the new root.

## What did not change

- **The recorder never writes `all:`.** A recording session keys its slot by
  the device it ran on, as before. `all:` is an author-side move — typically
  collapsing two platform slots after verifying both replay the same tools.
- **Legacy per-platform filename candidates** (`ios-iphone.trail.yaml`) are
  computed by segment truncation, not the lineage — no `all.trail.yaml` is ever
  probed.
- **Chain totality.** `chainFor` still returns a non-empty chain whose first
  element is the input classifier; an empty/blank input still yields an empty
  chain (`all` is a fallback for a device identity, not a substitute for one).
