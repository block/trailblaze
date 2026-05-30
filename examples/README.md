# Trailblaze Examples

Copy-me starting points. Each directory below is a **standalone, runnable Trailblaze
workspace** — its own `trails/config/`, its own scripted-tool sources, its own
`build.gradle.kts` — that you can copy into your project as a template, or run in place.

If you're here to learn how to author a trail and a typed tool, **start with
[`ios-contacts/`](ios-contacts/) (mobile) or [`wikipedia/`](wikipedia/) (web).**

## Trailmap-authoring examples — start here

These show the current best-practice authoring shape: typed
`trailblaze.tool<Input, Output>()` tools, auto-discovered from bare `.ts` files (no
`.yaml` manifests required), with a system prompt and a full trail suite.

| Example | Platform | What it teaches |
|---|---|---|
| [`ios-contacts/`](ios-contacts/) | iOS | **Canonical mobile reference.** 9 typed scripted tools (launch, search, open/create/delete, multi-step edit forms, contact-shape assertions, a composition example), 18 trails, and **tool unit tests** (`*.test.ts`). |
| [`wikipedia/`](wikipedia/) | Web | **Canonical web reference.** 9 typed scripted tools driving live `en.wikipedia.org` through the Playwright Native driver, 28 trails, a typed-tool demo, and a conditional-action demo. |
| [`playwright-native/`](playwright-native/) | Web | A minimal Playwright Native web workspace with its own bundled sample app, typed tools, and `*.test.ts` — the smallest end-to-end scripted-tool setup. |

## Sample target apps

Controlled apps that exist to be *driven* by trails (eval targets), not to teach
trailmap authoring:

| Example | Platform | What it is |
|---|---|---|
| [`android-sample-app/`](android-sample-app/) | Android | A small Android app used as a deterministic test target, with its own trails. |
| [`ios-sample-app/`](ios-sample-app/) | iOS | A minimal SwiftUI app mirroring the Android sample's Forms screen, used for iOS evals. |
| [`compose-desktop/`](compose-desktop/) | Desktop | A Compose for Desktop sample target. |
| [`playwright-electron/`](playwright-electron/) | Electron | A bundled Electron sample app driven through the Playwright driver. |

## Not examples

`build/`, `src/`, `dependencies/`, `package.json`, and `build.gradle.kts` at this level
belong to the `:examples` Gradle aggregate module (it also runs a local HTTPS server for
the web examples). Ignore them — they aren't starting points.

## Running an example

```bash
# Materialize the workspace SDK + per-trailmap typed bindings (run once after clone)
./trailblaze check --workspace examples/wikipedia

# Then drive or replay its trails against a connected device/browser
./trailblaze run --workspace examples/wikipedia <trail>
```
