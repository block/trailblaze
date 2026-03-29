---
title: "Screenshot Format Optimization (WebP Everywhere)"
type: decision
date: 2026-03-20
---

# Screenshot Format Optimization (WebP Everywhere)

Optimizing screenshot capture for memory-constrained on-device execution.

## Background

On-device remote device farm execution runs the Trailblaze agent in-process on the
Android device, where memory is heavily constrained. OOM crashes were observed during
long-running agent sessions. Investigation revealed two issues:

1. **Screenshots encoded as PNG regardless of config.** Both `AndroidOnDeviceUiAutomatorScreenState`
   and `AccessibilityServiceScreenState` called `bitmap.toByteArray()` with default parameters
   (PNG, quality 100) even though `ScreenshotScalingConfig` specified JPEG at 80%. PNG is
   lossless and produces byte arrays ~4x larger than lossy formats for typical UI screenshots.

2. **`AccessibilityServiceScreenState` had no scaling at all.** It captured at full device
   resolution (1080x1920) with no `ScreenshotScalingConfig`, while the instrumentation driver
   at least applied dimension scaling (though with the wrong format).

### LLM Provider Image Tiling Analysis

The 1536x768 default was chosen to optimize across all three LLM vision providers:

| Provider | Tiling Mechanism | 1536x768 Cost |
| :--- | :--- | :--- |
| OpenAI | Scales shortest side to 768px, tiles into 512x512 squares | For phone screenshots (~2.2:1 aspect), internally upscaled to ~768x1706 → 8 tiles |
| Anthropic | Proportional to pixel count, long edge capped at 1568px | ~1,600 tokens (fits under 1568px auto-downscale threshold) |
| Google Gemini | Tiles at 768x768, each tile = 258 tokens | 2 tiles (516 tokens) |

**1536x768** sits at the optimal boundary — fits under Anthropic's 1568px limit, matches
OpenAI's tile grid, and is exactly 2 Google tiles.

### Image Format Comparison

Theoretical estimates vs **measured CI data** (accessibility test suite, phone screenshot capture):

| Format | Estimated | Measured (768x1365 phone screenshot) |
| :--- | :--- | :--- |
| PNG (was actual default due to bug) | ~50-280 KB | **avg 101.6 KB** (baseline, main branch) |
| WebP 80% (final) | ~20-50 KB | **avg 25.1 KB** (after fix) |

**Measured ~4x reduction** from PNG → WebP at the same 1536x768 resolution.

### WebP Platform Support

WebP is universally supported across all platforms in the Trailblaze stack:

| Platform | Encoding | Decoding |
| :--- | :--- | :--- |
| Android (API 28+) | `Bitmap.CompressFormat.WEBP` (API 14+) / `WEBP_LOSSY` (API 30+) | Native |
| JVM host (Compose Desktop) | Skia via Skiko (already bundled) | Skia / Coil3 |
| Playwright (JVM) | Skia via Skiko | Skia |
| WASM (browser) | N/A (browser handles) | Native browser support (97%) |
| LLM providers | N/A | OpenAI, Anthropic, Google all accept `image/webp` |

### Resolution Decision

We initially tried 1024x512 to further reduce memory, but CI data showed:
- The format change (PNG → WebP) was the dominant win (~4x)
- Resolution reduction added ~2x more savings but degraded LLM quality
- Accessibility tests at 1024x512 had worse pass rates than main

Since the format fix alone provides sufficient memory savings, we kept full 1536x768
resolution to preserve LLM vision quality.

## What we decided

### 1. WebP as the universal default

`ScreenshotScalingConfig.DEFAULT` uses WebP 80% at 1536x768 for all platforms. `ON_DEVICE`
is an alias for `DEFAULT` — there is no longer a separate on-device config since the format
is now the same everywhere.

### 2. Fix encoding bug

Both `AndroidOnDeviceUiAutomatorScreenState` and `AccessibilityServiceScreenState` now use
the config's `imageFormat` and `compressionQuality` instead of hardcoded PNG defaults.

### 3. WebP encoding on all JVM platforms via Skia

Added `WEBP` to `TrailblazeImageFormat`. JVM host code (`BufferedImageUtils`,
`PlaywrightScreenState`) encodes WebP via Skia (`org.jetbrains.skia.Image.encodeToData`),
which is already bundled with Compose Desktop via Skiko. No new dependencies — Skiko is
made explicit in `trailblaze-playwright`'s `build.gradle.kts` but was already in the
transitive dependency tree.

### 4. Shared bitmap helpers (Android)

Extracted `scaleAndEncode()` and `annotateScreenshotBytes()` into `AndroidBitmapUtils` to
eliminate duplicated bitmap pipeline code between the two Android ScreenState implementations.
The shared `annotateScreenshotBytes()` includes OOM diagnostics via `MemoryDiagnostics`.

### 5. Memory profile during annotation

The `annotatedScreenshotBytes` path decodes the already-scaled `_screenshotBytes` (WebP,
~25 KB) back to a bitmap, draws set-of-mark overlays, and re-encodes to WebP. This means:

- **Peak bitmap memory**: one 768x1365 ARGB_8888 bitmap = ~4.0 MB (down from ~7.9 MB at
  full 1080x1920 before scaling was applied)
- **Double lossy compression**: the clean screenshot is WebP-encoded, then decoded for
  annotation and re-encoded. At 80% quality, one extra round-trip produces minimal artifacts.
  The old PNG path had zero degradation (lossless), but the memory savings justify the
  tradeoff — set-of-mark bounding boxes and labels are high-contrast and survive compression
  artifacts well.
- **Stored bytes**: both `screenshotBytes` (~25 KB) and `annotatedScreenshotBytes` (~25-40 KB)
  are WebP. Only the compressed byte arrays remain in memory; bitmaps are recycled immediately.

### 6. Filename detection

Screenshot filenames use `ImageFormatDetector.detectFormat()` which inspects the actual byte
content (RIFF/WEBP magic numbers), not the config. This ensures correct `.webp` file
extensions regardless of which encoding path produced the bytes.

## What changed

**Positive:**

- ~4x measured reduction in screenshot byte array size everywhere (PNG → WebP)
- ~2x reduction in peak bitmap memory during on-device annotation (768x1365 vs 1080x1920)
- Consistent WebP output across all platforms — no special cases or fallbacks
- Smaller CI artifacts for storage cost savings
- Shared bitmap code between Android ScreenState implementations
- No code changes needed at call sites — the default config handles it
- Full LLM vision quality preserved (same 1536x768 resolution, WebP ≥ JPEG quality)

**Negative:**

- Double lossy compression on the Android annotation path (encode clean → decode → annotate
  → encode). Minimal impact at 80% quality on high-contrast set-of-mark overlays.
  The host annotation path avoids this by annotating the full-res BufferedImage before scaling.
- Uses deprecated `Bitmap.CompressFormat.WEBP` on Android API 28-29 (suppressed warning).
  Unavoidable until minSdk is raised to 30.
