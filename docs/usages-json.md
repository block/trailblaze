# `trailblaze usages --json` — report contract

The machine-readable answer to "which trails use these tools?". Written for the consumer that gates
CI on it: a script deciding which trails to replay before a tool change lands, or an agent deciding
whether a tool is safe to delete.

This page is the contract. The TypeScript types are generated from the same Kotlin models
(`sdks/typescript/src/generated/usages-report.ts`) and CI byte-diffs them, so the types and the
runtime shape cannot disagree — but the types cannot express what a field *means*, which is what
follows.

**Getting the types into your repo: copy the file.** It is not importable from outside a Trailblaze
checkout, and is not meant to be. `@trailblaze/scripting` is unpublished, and the declaration bundle
the CLI extracts into a workspace (`.trailblaze/sdk/dist`) is the *scripted-tool authoring* surface —
a trailmap tool neither produces nor reads a usages report, so these types are deliberately absent
from it. The file has no imports and no runtime code, so vendoring it works: paste it in, or
regenerate against the CLI version you invoke. This page, not the file, is what tells you the field
semantics either way — decoding by key straight off it is a perfectly good consumer.

For flags and defaults, see [`trailblaze usages`](CLI.md#trailblaze-usages).

## Reading stdout

`--json` writes **one JSON document and nothing else** to stdout — you can pipe it straight into a
parser. Configuration breadcrumbs (which `trailblaze.yaml` was loaded, which trails directory was
resolved), deprecation notices, and error messages all go to **stderr**. This is locked by a test,
not a convention: `UsagesCommandTest` captures stdout and fails if anything but the document lands
there.

Exit codes:

| Code | Meaning | Cases | What a consumer should do |
|---|---|---|---|
| `0` | A report was produced. | — | Read it. A report can be **incomplete** and still exit 0 — see [Incompleteness](#incompleteness-is-reported-not-hidden). |
| `2` | Infrastructure failure. | No `esbuild` reachable; `git worktree add` failed for the ref. | Treat as "the framework tier is unavailable", **not** as "no tools changed". Fall back to whatever coarser derivation you had. |
| `3` | Misuse. | No tools named and no `--changed-since`; an unusable `--trails`; no trails directory; no trailmaps directory above the trails root; not inside a git repository; the ref does not resolve to a commit. | Fix the invocation (an unresolvable ref usually means fetch first). |

A non-zero exit means **no JSON at all**. In particular `--changed-since` requires `esbuild` on
`PATH` (or in a reachable `sdks/typescript/node_modules`) and fails the whole command without it,
rather than degrading per tool. Plain `usages <names…>` needs no esbuild — only the changed-set
derivation bundles anything.

## Shape

```json
{
  "schemaVersion": 1,
  "trailsRoot": "/repo/legacy-trails",
  "scannedRoots": ["/repo/legacy-trails", "/repo/jobs"],
  "tools": [
    {
      "tool": "myapp_launchSignedIn",
      "changeKind": "modified",
      "sourcePaths": ["/repo/trailblaze-config/trailmaps/myapp/tools/myapp_launchSignedIn.ts"],
      "usages": [
        {
          "trail": "checkout/add-item-to-cart",
          "path": "checkout/add-item-to-cart.trail.yaml",
          "root": "/repo/legacy-trails",
          "title": "Add an item to the cart",
          "classifiers": ["android"],
          "skip": { "ios": "blocked on an upstream bug" },
          "steps": [
            {
              "stepIndex": 0,
              "step": "Launch the app and sign in",
              "classifiers": ["android"],
              "declaredClassifiers": ["android", "ios"]
            }
          ],
          "devices": ["android-phone", "android-tablet", "android", "ios", "ios-iphone"],
          "invokingDevices": ["android-phone", "android-tablet", "android"]
        }
      ]
    }
  ],
  "warnings": ["…"],
  "diagnostics": [{ "kind": "…", "subject": "…", "message": "…", "severity": "incompleteness" }],
  "changedSince": {
    "ref": "main",
    "resolvedSha": "27df5256afc3…",
    "added": [], "removed": [], "modified": ["…"], "impactedViaCallers": ["…"],
    "workingTree": { "headSha": "6160fc1366e8…", "dirty": false }
  },
  "generatedBy": "v20260826.150841.abc123 (Internal)"
}
```

Keys appear in the order the producer emits them, and newer fields — `generatedBy`,
`devices`, `invokingDevices` — sit **last** rather than beside the fields they read with. That is
deliberate and will keep happening: appending is what keeps a Kotlin consumer's `componentN()`
destructuring working across a version, and inserting a field where it reads better silently changes
what every later one returns. Read by key, never by position.

### Top level

| Field | Notes |
|---|---|
| `schemaVersion` | See [Versioning](#versioning). |
| `trailsRoot` | Absolute path of the primary trails root. |
| `scannedRoots` | Absolute paths of every root actually **read**, primary first. **Read this, not `trailsRoot`, to know what a zero-usage answer covered** — a root that was asked for but unreadable is absent here and named in `diagnostics` instead. |
| `tools` | One entry per queried tool, in query order, **including tools with zero usages**. |
| `warnings` | Every reason the report may be incomplete, as prose. Exactly the messages of the `severity: "incompleteness"` diagnostics, same order — hints are excluded. |
| `diagnostics` | Every diagnostic, classified, hints included. Gate on these. A superset of `warnings`. |
| `changedSince` | Present only when `--changed-since` derived the tool set. |
| `generatedBy` | The Trailblaze version that produced the report — the bare version string. `trailblaze --version` prints the same value prefixed with `Trailblaze` and a space; this field carries neither. Null if the producer recorded none. `schemaVersion` says which *fields* to expect; this says which *behavior* produced the values. |

Paths are absolute. That makes an **archived** report machine-specific — accepted, because a
consumer resolving a usage needs a real filesystem anchor and the alternative is relative to an
unstated cwd. Pair an archived report with `generatedBy` and `changedSince.workingTree` and treat
the paths as provenance rather than as an index.

### `tools[]`

| Field | Notes |
|---|---|
| `tool` | The name as queried. |
| `changeKind` | Why this tool is in the set: `named`, `added`, `removed`, `modified`, `impactedViaCallers`. |
| `sourcePaths` | Absolute path(s) of the scripted-tool script(s) declaring it. Empty for built-in and class-backed tools, and for a `removed` tool. A **list** because two trailmaps may legally declare one name. |
| `usages` | Trails that **directly** invoke it. Empty is a first-class answer. |

`changeKind` matters most for `removed`: those usages are trails that are now **broken**, which
reads nothing like the other kinds.

### `tools[].usages[]`

| Field | Notes |
|---|---|
| `trail` | Root-relative id (suffix stripped). Not unique across roots — pair with `root`. |
| `path` | Root-relative file path. |
| `root` | Which of `scannedRoots` this hit came from. |
| `title` | The trail's `config.title`, if any. |
| `classifiers` | Recording keys that invoke the tool, **as authored**. Raw fact, not a device list. |
| `skip` | The trail's per-classifier `config.skip:` map, verbatim. |
| `steps` | The individual invoking steps. |
| `devices` | Every device classifier the **trail** declares direction for. May include `all`. |
| `invokingDevices` | The subset of `devices` that actually reaches the tool. |

**Key each lane on `invokingDevices`, not on `classifiers`.** Replay resolves each device to its
single *closest* matching recording key, so an `all:` invocation is shadowed on any device whose step
declares something more specific. In this trail:

```yaml
- step: Add an item
  recording:
    all:
      - myapp_addItem: {}
    ios-iphone:
      - myapp_signOut: {}
```

`classifiers` is `["all"]`, but an iPhone never touches `myapp_addItem`. `invokingDevices` omits
`ios-iphone`; `devices` still lists it. `classifiers` and `steps[].declaredClassifiers` remain
available for a consumer that wants the authored facts rather than the derivation.

**An absent `invokingDevices` is not an empty one.** The field is additive at `schemaVersion` 1, so a
report from a CLI predating it omits the key — and a consumer that decodes it into a non-null list
gets `[]`, reads "reaches no device", and replays nothing. That is the exact miss the field exists to
prevent. Decode into a nullable and fall back to `classifiers` when the key is missing, or require a
`generatedBy` you know emits it. The same caution applies to every additive field below.

**Multi-device configurations resolve through the configuration, not around it.** A configuration's
legs are keyed by its *name* (`pos-pair:`), which no member device's lineage contains — selection is
what makes them reachable, by putting the configuration's name at the head of the chain. So a step
keyed only `pos-pair:` lists both cast devices in `invokingDevices`, and `devices` lists the members
rather than the configuration name. A trail may declare at most one configuration (replay rejects
more), and it always replays with that configuration selected — there is no configuration-free
session to fall back on. So a cast device whose step declares a leg for the configuration resolves
that leg, and when it invokes something else the device is omitted — even if a broader `all:` leg
invokes the tool — exactly as with plain closest-wins shadowing; a step with no configuration leg
still falls through to the member's broader keys. A declared classifier the configuration does not
cast never runs the trail at all, so it never appears in `invokingDevices`, though `devices` still
lists it.

### `steps[]`

`stepIndex` is the 0-based index into `trail:`, or **null for the trailhead**. `classifiers` are the
step's invoking keys; `declaredClassifiers` are every key the step declares, invoking or not — the
input the closest-wins resolution above runs against.

### `changedSince`

`added` / `removed` / `modified` are facts about bytes: each tool's source is hashed **together with
its resolved import closure**, so editing a shared helper flags every tool that imports it.
Detection is deliberately conservative — a comment-only edit flags, because a false `modified` costs
a redundant run while a false "unchanged" silently skips one.

`impactedViaCallers` is **an inference**, not a fact: tools that did not themselves change but whose
bundled implementation dispatches one that did. Tool→tool dispatch goes through the host at runtime,
so it never enters the import closure. It over-reports by design and cannot see a callee name
computed at runtime. Keep it separate: a consumer that wants only certainty ignores it; one
validating blast radius replays it too.

`workingTree` is the other side of the comparison — the `HEAD` the tools were read from, and whether
tracked content differed from it. Null when it could not be read. `dirty` excludes untracked files:
an untracked tool is work in progress that the comparison already reports as `added`.

## Incompleteness is reported, not hidden

A report can be incomplete and still exit 0. **A non-empty `warnings` means a consumer gating on
"zero usages" should fail open** rather than treat the tool as unused.

Gate on `severity`, not on a list of kinds you recognize:

| `severity` | Meaning | In `warnings`? |
|---|---|---|
| `incompleteness` | The scan could not see something, so usages may be missing. | Yes |
| `hint` | Nothing went wrong — a note about the *query* you made. | No |

A newer CLI can add kinds, and `severity` is what lets an older reader classify one it has never
heard of without guessing. Read `diagnostics` to see everything; read `warnings` for the fail-open
trigger alone. Reading *every* diagnostic as a fail-open trigger is what makes `usages tapOn` — a
built-in that a given workspace happens not to use — fail a gate permanently, with nothing to fix.

| `kind` | Subject | Severity | What is unknown |
|---|---|---|---|
| `trail-unparseable` | the trail file | `incompleteness` | That file's usages. One file's worth. |
| `root-unscanned` | the root path | `incompleteness` | A whole subtree's usages. Much larger blast radius than the above. |
| `tool-bundling-failed` | the tool name | `incompleteness` | That tool's import closure on one side, so edits to files it imports cannot flag it. |
| `tool-comparison-degraded` | the tool name | `incompleteness` | The two sides were not comparable like for like. Either one side resolved an import closure and the other could not (the tool is counted as `modified` — fail open), or neither did and it was compared blind to its imports. |
| `tool-comparison-excluded` | the declaring trailmap directory | `incompleteness` | Those tools were left out of the comparison entirely: they are gitignored (e.g. a trailmap staged from another repo's pinned clone), so no ref checkout contains them. Validate their changes against the owning repo. |
| `tool-inventory-incomplete` | `base` or `current` | `incompleteness` | Part of an inventory scan failed (malformed descriptor, a descriptor naming a missing script, ambiguous bare `.ts`), so a tool may be missing from it. Under `--changed-since` that reads as `added` or `removed`; in explicit mode the subject is always `current` and it means `sourcePaths` may be empty for a tool that does exist. |
| `caller-scan-unavailable` | `impactedViaCallers` | `incompleteness` | Some tools could not be scanned for dispatch edges, so that field understates. One diagnostic naming every unscanned tool. |
| `tool-not-in-scripted-inventory` | the tool name | `hint` | Nothing. A queried name with zero usages that is also absent from the scripted inventory is either a typo or a built-in. Suppressed when the inventory scan was itself incomplete — the absence is then explained by `tool-inventory-incomplete` above, and spelling is the one answer that would be wrong. |

Match the kinds you handle. Treat an **unrecognized** kind as "something I don't understand" and let
its `severity` decide whether to fail open — not a parse error either way.

## Versioning

- **Additive changes do not bump `schemaVersion`.** New fields and new `kind` / `changeKind` values
  ship at version 1.
- **Consumers MUST ignore unknown fields.** A kotlinx-serialization consumer needs
  `Json { ignoreUnknownKeys = true }`; it is not the default and a new field will otherwise throw.
- **`kind`, `changeKind` and `severity` are strings, not enums**, for the same reason: deserializing
  into a closed enum fails on the first value a newer CLI adds. Match what you handle, ignore the
  rest.
- **An additive field is ABSENT from an older report, not empty.** A default-valued decode turns
  "this producer never computed it" into a confident `[]` / `false`, which is how an additive field
  becomes a silent wrong answer. Decode fields you gate on as nullable, or require a `generatedBy`
  you know emits them. `invokingDevices`, `devices`, `sourcePaths`, `severity`,
  `changedSince.workingTree` and `generatedBy` itself all shipped this way.
- **A new field is appended, not inserted.** It lands at the end of its object rather than beside
  the fields it reads with, so key order is not stable across versions and carries no meaning.
  Nothing here is positional — read by key. (A Kotlin consumer destructuring these classes gets the
  reason: inserting a field shifts every later `componentN()`, which is a break no shim can undo.)
- **Gate on `schemaVersion` for breaking changes only** — a removed or re-meaning'd field. Refuse a
  version you do not know rather than guessing.
- **A tool rename reads as `removed` + `added`**, not as one event. Nothing tracks identity across a
  rename, so a consumer that wants to treat it as a rename has to pair them itself.
