# Multi-device trails

Load this when one trail drives more than one device: a seller and a buyer display, a phone and a
tablet, a device plus a browser. A *companion device* here is a second device bound beside the
start device. It is unrelated to companion mode in [`SKILL.md`](../SKILL.md#companion-mode), where
a human watches or steers a session.

## Trail side

`config.devices` declares a **named configuration**: an entry with an inner `devices:` map. Each
member is keyed by a name and carries `classifier`, `driver`, and an optional `target` that
overrides `config.target` for that member. The **first member is the start device**, and the
trailhead runs there.

```yaml
config:
  devices:
    seller-buyer:          # configuration name
      devices:
        seller: { classifier: android-tablet }
        buyer: { classifier: android-phone }
```

Single-device entries (`android-phone: {}`) can sit beside a configuration. Such a trail runs its
single-device legs when no companions are bound, and its configuration when they are. A
configuration-only trail needs its companions bound on every run.

## Recordings

A step's `recording:` leg is keyed by the **configuration name**, not by a classifier. It starts on
the first member and moves between members through recorded `switchDevice` calls. Companions do not
get legs of their own. The trailhead's leg is keyed the same way.

"Every step recorded" means every leg a run will replay: the configuration's legs for a run with
companions bound, plus the classifier legs for any single-device entries. A single-device run of a
configuration trail records nothing; bind the cast.

## Handover

[`switchDevice: { name: <member> }`](https://block.github.io/trailblaze/generated/functions/custom/switchDevice/)
makes that member the active device. It is session state: it carries across steps until the next
switch, with no reset per step and no implicit return to the start device. Call it as its own step;
it takes effect from the next dispatch. It needs a host runner, so it cannot run on-device. A
trailhead `teardown` runs only if the trail ends on the device the trailhead set up, so switch back
before the trail ends if you need it.

## Recording

Boot every device in the cast, then run with AI on and the companions bound for that run. The start
device is `-d`; never bind it:

```bash
trailblaze run <trail> -d <start-device> --bind buyer=<device-id> --self-heal=false
```

The run saves each step's leg under the configuration name; `--configuration` picks one when the
trail declares several. Multi-device runs refuse self-heal. To record interactively,
`trailblaze session start -d <start-device> --bind seller=...,buyer=...` then
`trailblaze session save -d <start-device> --configuration <name>` saves the roster as a new
configuration. Before you call it done, replay with AI off: `--use-recorded-steps
--no-save-recording`, companions bound the same way.

Flags: [CLI reference](https://block.github.io/trailblaze/CLI/) (`run`, `session start`,
`session save`). Binding without flags: `TRAILBLAZE_DEVICE_BINDINGS` and
`TRAILBLAZE_DEVICE_CONFIGURATION` in the
[configuration reference](https://block.github.io/trailblaze/configuration/).

## Verification

A multi-device replay can pass while proving nothing about the handover. Two rules:

- **Assert something that differs across the cast**, right after the handover. Then delete the
  `switchDevice` and replay: the trail must fail. If it still passes, the assert does not
  discriminate.
- **Put setup before the handover**, in the `trailhead:` or any leg that has not yet switched. Setup
  inside a switched leg runs on whichever device is active, and can clear the state the assert is
  meant to detect.
