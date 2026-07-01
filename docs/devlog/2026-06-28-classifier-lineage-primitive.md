---
title: "Classifier lineage: the shared most-specific-first primitive"
type: decision
date: 2026-06-28
---

# Classifier lineage: the shared most-specific-first primitive

Finalizing the closest-wins core of the [unified trail format](2026-05-22-trail-yaml-unified-syntax.md):
the per-classifier fallback chain is now a property of the classifier itself,
computed once in `trailblaze-models`, instead of a chain each caller assembles
by hand.

## Background

The unified trail format keys each step's recordings by device classifier
(`android-phone:`, `ios:`) and resolves them **closest-wins**: for the device
under test, walk from the most specific classifier up to its family and use the
first recording that matches. The waypoint schema (classifier-keyed assertions)
resolves exactly the same way — same lookup, different payload.

Until now the most-specific-first chain was **caller-supplied**. The unified
adapter took a `List<TrailblazeDeviceClassifier>` and walked it verbatim; the
ordering and completeness of that chain lived in every call site. Two problems
fell out of that:

1. **No single source of truth.** Nothing guaranteed the chain was total,
   correctly ordered, or consistent between the trail adapter and the waypoint
   resolver — the two consumers that must agree.
2. **The chain didn't actually reconstruct the on-disk vocabulary.** Device
   providers emit a device's identity as a *broad-first segment list*
   (`[ios, iphone]`, `[android, phone]`) — the same shape
   `computePossibleFileNamesForDeviceClassifiers` joins with `-` to build
   legacy per-platform filenames (`ios-iphone.trail.yaml`). But the adapter
   walked those raw segments, so a recording keyed by the **compound**
   classifier `ios-iphone:` (the vocabulary the spec and the migration use)
   never matched on a real device — only the bare family key `ios:` did. The
   compound recording was silently unreachable.

## What we decided

### Build the lineage as a real primitive in `trailblaze-models`

`TrailblazeClassifierLineage` turns a classifier into its **total, ordered
(most-specific-first) ancestor chain**. It's the keystone both the trail
adapter and the waypoint schema consume, so the fallback rule is defined once.

Parent resolution for a classifier `C`:

1. an **explicit override** if one is registered for `C`; otherwise
2. the **string-derived** parent — drop the trailing `-segment`.

```
android-phone-37  →  android-phone  →  android
ios-ipad          →  ios
android           →  (root)
```

### Derivation-based, so arbitrary depth works for free

String-derivation is the default because it makes **arbitrary classifier depth
work with no schema or parser change**. A deeper classifier resolves up through
every intermediate to its family purely by dropping hyphen segments:

```
android-phone-37     →  android-phone     →  android
android-phone-37-xl  →  android-phone-37  →  android-phone  →  android
```

No enum entry, no registration, no parser edit — a new sub-classifier is
expressible the moment someone writes it. This is the property that lets the
classifier vocabulary grow without touching the format.

### Explicit parent overrides for irregular families

Some hardware families don't string-derive because the family name isn't a
prefix of its platform. A foldable hardware family that runs Android, say,
named `foldable`, has no `-segment` to drop, so by derivation alone it would be
its own root and never fall back to `android`. For those, a downstream module
registers an explicit parent at startup:

```
registerParentOverride(child = "foldable", parent = "android")
//  foldable-x2  →  foldable  →  android
```

Open-source classifier families (`android-phone`, `ios-ipad`, …) all
string-derive to their platform, so the override registry ships **empty**; it
exists for distribution-specific irregular families wired in by the
distribution that owns them. Registration happens during the device-classifier
resolution every runner does *before* decoding a trail, so an override is
always present before any decode that could rely on it.

### Totality and the cycle guard

`chainFor` is total: it always returns a non-empty list whose first element is
the input classifier, and it always terminates. String-derivation strictly
shortens the string each step; a malformed override cycle (`a → b → a`) is
broken by a visited-set, so a bad registration can degrade the fallback but can
never hang resolution.

### The segment → compound → chain bridge

`resolutionChain(deviceClassifiers)` takes the device's broad-first segment list
exactly as a provider emits it, joins the segments into the compound identity,
and expands that through the lineage:

```
[ios, iphone]   →  "ios-iphone"   →  [ios-iphone, ios]
[android, phone]→  "android-phone"→  [android-phone, android]
[foldable, x2]  →  "foldable-x2"  →  [foldable-x2, foldable, android]   (with the override above)
```

This is the same join `computePossibleFileNamesForDeviceClassifiers` already
applies for legacy filenames, so the in-file unified resolution and the legacy
filename resolution now speak the same compound vocabulary — and a recording
keyed by the compound `ios-iphone:` finally resolves on a real device.

## Surface syntax — reaffirmed, not changed

Finalizing the lineage did **not** move the surface syntax. The classifier-keyed
+ closest-wins shape is the right cross-cutting pattern (the waypoint schema
deliberately mirrors it), so the format keeps:

- **No all-platform fallback.** Selectors are platform-specific; a step needs
  at least one classifier declared. Per-family sharing comes from the lineage
  (`ios:` covers iPhone + iPad), not from a step-level catch-all.
- **Family keys as the multi-device catch-all.** `android` / `ios` are the
  broadest classifiers; closest-wins falls through to them.
- **`recordable: false`** — author intent that a step is always LLM-handled.
- **Explicit `<classifier>: []`** — intentional no-op for a device class,
  distinct from accidental absence in the coverage report.

## What changed

**Positive:**

- One definition of the fallback chain, shared by the trail adapter and the
  waypoint resolver — they can't drift.
- Compound classifier recordings (`ios-iphone:`) resolve correctly on real
  devices; the spec's vocabulary and runtime behavior finally agree.
- Arbitrary classifier depth needs no code change.
- The caller contract is simpler: pass the device's classifier segments as the
  provider emits them; the lineage owns ordering, completeness, and family
  fallback.

**Negative / watch-outs:**

- The override registry is global startup state. It's idempotent and registered
  on the classify-before-decode path, but a distribution that resolves
  classifiers through a non-standard path must ensure its overrides are
  registered before it decodes a trail keyed above the string-derivable level.
- `TrailblazeClassifierLineage` is a new public surface in `trailblaze-models`
  (it feeds the TypeScript binding codegen's source-of-truth module), so its
  shape is now part of the binary-compatibility baseline.
