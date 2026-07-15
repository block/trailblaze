# Android Sample App

A small Compose Android app used as a deterministic Trailblaze test target. It ships its own
trails under `trails/` and a workspace config under `trails/config/` (the `sampleapp` target). The
app has tabs for Forms, Lists, Loading, Catalog, Swipe, and Settings — enough surface to demonstrate
the common trail-authoring patterns without depending on any external service.

Materialize the workspace once after clone, then drive or replay its trails against a connected
emulator:

```bash
trailblaze check --workspace examples/android-sample-app
trailblaze run --workspace examples/android-sample-app trails/forms/text-input.trail.yaml
```

## How arguments work — `trails/forms/text-input-args.trail.yaml`

This trail is the "how do parameterized trails work" reference. It's the same Forms flow as
`trails/forms/text-input.trail.yaml`, but instead of hardcoding the name and email it **declares
them as typed arguments** in a `config.args:` block and references them as `{{args.name}}` /
`{{args.email}}` tokens in the step prose:

```yaml
config:
  args:
    name:
      type: string
      default: "Jane Doe"
    email:
      type: string
      default: "jane@example.com"
trail:
  - step: Tap the "Name" field and type "{{args.name}}"
  - step: Tap the "Email" field and type "{{args.email}}"
```

Each arg has a `default:`, so it's **optional** — the trail runs with zero arguments and falls back
to the declared defaults. (Omit `default:` to make an arg **required**; the run then fails fast when
a caller doesn't supply it.) Supported types are `string`, `integer`, and `boolean`.

Supply or override values at run time with `--arg KEY=VAL` (repeatable) or `--args-file <yaml|json>`:

```bash
# Defaults run — uses "Jane Doe" / "jane@example.com":
trailblaze run --workspace examples/android-sample-app trails/forms/text-input-args.trail.yaml

# Override run — the typed and verified values reflect the passed args:
trailblaze run --workspace examples/android-sample-app trails/forms/text-input-args.trail.yaml \
  --arg name="Sam Edwards" --arg email="sam@example.com"
```

`trailblaze check` validates the argument tokens: every `{{args.x}}` must reference a declared arg,
so a typo like `{{args.emial}}` fails the check rather than silently interpolating to an empty
string.

> Arguments are **non-sensitive by design** — their values are logged in cleartext, persisted into
> logs/recordings, and surfaced to the LLM. Route passwords, tokens, or other secrets through
> `--secret` memory instead.

## Layout

```text
trails/
├── config/                      # Workspace anchor + the `sampleapp` target (see config/README.md)
├── forms/                       # Natural-language authoring trails (text-input, args, generated-user)
├── catalog/ lists/ loading/ swipe/ settings/ taps/   # More authoring trails, grouped by feature
├── mcp-tools-demo/              # Trails exercising MCP-backed tools
├── android-ondevice-accessibility/     # Recorded per-driver variants (accessibility driver)
└── android-ondevice-instrumentation/   # Recorded per-driver variants (instrumentation driver)
```

See [`trails/config/README.md`](trails/config/README.md) for the workspace config layout and how
walk-up discovery finds it.
