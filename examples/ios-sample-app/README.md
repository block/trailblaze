# ios-sample-app

Minimal SwiftUI app that mirrors the [`android-sample-app`](../android-sample-app/) Forms tab. Built to serve as a controlled target for Trailblaze iOS evals — currently just the [clipboard round-trip](../../../trails/eval/ios/sample-app/clipboard-round-trip.trail.yaml).

## What's here

```
ios-sample-app/
├── IosSampleApp/               ← SwiftUI sources (add new .swift files here)
│   ├── IosSampleAppApp.swift   ← @main app entry
│   ├── FormsScreen.swift       ← single screen: Name + Email, Submit, Clear All
│   └── Assets.xcassets/
├── IosSampleApp.xcodeproj/     ← Xcode project (uses synchronized folder groups)
└── build-and-install.sh        ← xcodebuild + simctl install wrapper
```

Bundle id: `xyz.block.trailblaze.examples.iossampleapp`.

## Requirements

- **Xcode 16+.** The `.xcodeproj` uses `PBXFileSystemSynchronizedRootGroup` (`objectVersion = 77`), which earlier Xcode versions will silently rewrite into a different format. The build script enforces this — it fails fast if it sees Xcode 15 or older.
- A booted iOS Simulator (`xcrun simctl list devices booted` should show exactly one).

## Build and install

```bash
./build-and-install.sh
```

The script builds for `iphonesimulator` and installs the resulting `.app` onto the booted simulator. xcodebuild output is captured to `build/build.log`; on failure the last 80 lines are written to stderr so build problems are visible.

## Running the clipboard eval against this app

```bash
./examples/ios-sample-app/build-and-install.sh
./trailblaze run trails/eval/ios/sample-app/clipboard-round-trip.trail.yaml --device ios
```

## Adding new screens

Because the Xcode project uses synchronized folder groups, **no `pbxproj` edit is needed** to add a new Swift file — drop it into `IosSampleApp/` and Xcode picks it up automatically. Same applies to assets under `IosSampleApp/Assets.xcassets/`.

When extending the app to mirror more of the Android sample app's tabs (Lists, Taps, Catalog, Swipe), keep `accessibilityIdentifier` values aligned with the Android side's `testTag` values so the same eval trail YAML can drive both platforms with minimal divergence.
