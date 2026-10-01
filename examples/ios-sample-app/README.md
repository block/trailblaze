# ios-sample-app

Minimal SwiftUI app that mirrors the [`android-sample-app`](../android-sample-app/) Forms tab. Built to serve as a controlled target for Trailblaze's iOS evals: a clipboard round-trip and a WKWebView content descent.

## What's here

```
ios-sample-app/
├── IosSampleApp/               ← SwiftUI sources (add new .swift files here)
│   ├── IosSampleAppApp.swift   ← @main app entry (NavigationStack root)
│   ├── FormsScreen.swift       ← Name + Email, Submit, Clear All, link to Web Content
│   ├── WebContentScreen.swift  ← WKWebView fixture: page content in a separate process
│   └── Assets.xcassets/
├── IosSampleApp.xcodeproj/     ← Xcode project (uses synchronized folder groups)
└── build-and-install.sh        ← xcodebuild + simctl install wrapper
```

Bundle id: `xyz.block.trailblaze.examples.iossampleapp`.

## Requirements

- **Xcode 16+.** The `.xcodeproj` uses `PBXFileSystemSynchronizedRootGroup` (`objectVersion = 77`), which earlier Xcode versions will silently rewrite into a different format. The build script enforces this — it fails fast if it sees Xcode 15 or older.
- A booted iOS Simulator (`xcrun simctl list devices booted` should show exactly one).
- **A model configured for the agent.** The `step` and `ask` commands below decide what to do by
  asking an LLM, and a fresh install has none selected, so they fail until you pick one
  (`trailblaze config llm <provider/model>` — the provider is required, and
  `trailblaze config models` prints every valid value in that form) and, if that provider needs
  a key, put it in your environment (a local Ollama model needs none).
  [Getting Started](../../docs/getting_started.md) covers both, once, for every example here.

## Build and install

```bash
./build-and-install.sh
```

The script builds for `iphonesimulator` and installs the resulting `.app` onto the booted simulator. xcodebuild output is captured to `build/build.log`; on failure the last 80 lines are written to stderr so build problems are visible.

## Driving this app

[Build and install](#build-and-install) the app first — that installs the bundle but does not open
it, so bring it to the foreground before the first command. From there every Trailblaze command
works against it directly, with no trail file and no workspace:

```bash
xcrun simctl launch booted xyz.block.trailblaze.examples.iossampleapp

trailblaze step "Type Ada Lovelace into the Name field" --device ios
trailblaze step "Tap Submit" --device ios
trailblaze ask "What does the submission result say?" --device ios
```

Each `step` performs one action, so filling the field and submitting it are two of them. The `ask`
then reports `Name: Ada Lovelace Email:` — the label `submit()` renders.

## What the evals cover

Two pinned eval trails exercise this app, one per iOS driver. They run in CI and are not included in
this directory:

- **Clipboard round-trip** (host driver) — set the simulator pasteboard, paste into the Name field,
  submit, and assert the pasted text is rendered. Regression coverage for `mobile_setClipboard` and
  `mobile_pasteClipboard` on iOS.
- **WKWebView content descent** (axe driver) — assert that page content inside
  [`WebContentScreen`](IosSampleApp/WebContentScreen.swift)'s web view reaches the accessibility
  tree. iOS renders web content in a separate process, so an in-process accessibility walk cannot
  see it at all; this screen is the fixture that proves the descent works. Driving that screen with
  the axe driver needs an `axe` whose `describe-ui` accepts `--include-web-content` — on an older
  one the page's content never arrives and every assertion against it fails.

## Adding new screens

Because the Xcode project uses synchronized folder groups, **no `pbxproj` edit is needed** to add a new Swift file — drop it into `IosSampleApp/` and Xcode picks it up automatically. Same applies to assets under `IosSampleApp/Assets.xcassets/`.

When extending the app to mirror more of the Android sample app's tabs (Lists, Taps, Catalog, Swipe), keep `accessibilityIdentifier` values aligned with the Android side's `testTag` values so the same eval trail YAML can drive both platforms with minimal divergence.
