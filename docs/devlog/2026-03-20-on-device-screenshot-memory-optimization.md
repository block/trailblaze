---
title: "Screenshot Format Optimization (WebP Everywhere)"
type: devlog
date: 2026-03-20
---

# Screenshot Format Optimization (WebP Everywhere)

## Summary

Switched all screenshot encoding to WebP across every platform (Android, JVM host, Playwright)
by fixing a PNG encoding bug and adding Skia-based WebP encoding on JVM. Measured ~4x
reduction in screenshot sizes at equivalent visual quality.

## The Problem

On-device ATF runs were hitting OOM during long agent sessions. Screenshots were a major
memory contributor due to two bugs:

1. **Encoding bug**: `bitmap.toByteArray()` defaulted to PNG (lossless, ~100 KB per screenshot)
   even though the config specified JPEG. Both `AndroidOnDeviceUiAutomatorScreenState` and
   `AccessibilityServiceScreenState` had this bug.

2. **Missing scaling**: `AccessibilityServiceScreenState` had no `ScreenshotScalingConfig` at
   all — full 1080x1920 device resolution, PNG encoded.

## Measured CI Results

Compared screenshots from the same test (`creditCardSignature` accessibility) across builds:

| Config | Format | Resolution | Avg size/screenshot |
| :--- | :--- | :--- | :--- |
| PNG bug (before) | PNG | 1080x1920 | **101.6 KB** |
| Low-res test | WebP | 910x512 | **12.8 KB** |
| Full-res WebP (final) | WebP | 768x1365 | **25.1 KB** |

The format change (PNG → WebP) provided the ~4x reduction. Resolution reduction added
another ~2x but degraded LLM quality, so we kept full 1536x768 resolution.

## What We Changed

| Change | Impact |
| :--- | :--- |
| Fix encoding bug in both Android ScreenState classes | PNG → WebP, ~4x smaller |
| Apply `ScreenshotScalingConfig` to `AccessibilityServiceScreenState` | Was missing entirely |
| Add `WEBP` to `TrailblazeImageFormat` + `ImageFormatDetector` | Framework-wide WebP support |
| WebP encoding on JVM host via Skia (`BufferedImageUtils`) | Host screenshots now WebP too |
| WebP encoding in Playwright via Skiko | Consistent output everywhere |
| Unify `DEFAULT` and `ON_DEVICE` configs to WebP 1536x768 | Single config, no special cases |
| Extract `scaleAndEncode()` / `annotateScreenshotBytes()` into `AndroidBitmapUtils` | Shared code, -34 lines |

### Platform encoding matrix

| Platform | Encoder |
| :--- | :--- |
| Android instrumentation | `Bitmap.CompressFormat.WEBP` / `WEBP_LOSSY` |
| Android accessibility | Same |
| JVM host (Maestro driver) | Skia via Skiko (bundled with Compose Desktop) |
| Playwright | Skia via Skiko |
| WASM/browser (decoding only) | Native browser WebP support |

### Memory analysis

- **Stored bytes**: ~25 KB WebP per screenshot in memory (was ~100 KB PNG)
- **Peak bitmap during annotation**: 768x1365 = ~4.0 MB (was 1080x1920 = ~7.9 MB)
- **Double lossy compression**: Android annotation path encodes clean → decodes → annotates →
  re-encodes. Minimal quality impact at 80%. Host path avoids this by annotating before scaling.

## Also Fixed

- `List.removeFirst()` → `removeAt(0)` in `PromptStepStatus.kt`. Java 21+ API not available
  on Android runtime. Caused ~31 test failures across 3 CI steps.
