# Android World Benchmarks

Trailblaze test definitions adapted from [Google Research Android World](https://github.com/google-research/android_world).

## Quick Start

```bash
# Run a single benchmark
./trailblaze trail opensource/trails/benchmarks/android_world/clock/timer_entry/android.trail.yaml

# Run all benchmarks
bash opensource/trails/benchmarks/android_world/benchmark.sh

# Run a subset
bash opensource/trails/benchmarks/android_world/benchmark.sh --filter clock
```

## Overview

[Android World](https://github.com/google-research/android_world) is a benchmark for autonomous AI agents on Android. This directory contains **79 Trailblaze-compatible benchmarks** covering 15 apps.

| App | Package | Tasks |
|-----|---------|-------|
| Simple Calendar Pro | `com.simplemobiletools.calendar.pro` | 7 |
| Markor | `net.gsantner.markor` | 11 |
| Broccoli (Recipes) | `com.flauschcode.broccoli` | 12 |
| Pro Expense | `com.arduia.expense` | 8 |
| Simple SMS Messenger | `com.simplemobiletools.smsmessenger` | 6 |
| Retro Music | `code.name.monkey.retromusic` | 4 |
| OsmAnd+ | `net.osmand` | 3 |
| Clock | `com.google.android.deskclock` | 3 |
| Camera | `com.android.camera2` | 2 |
| Audio Recorder | `com.dimowner.audiorecorder` | 2 |
| Contacts | `com.google.android.contacts` | 2 |
| Files | `com.android.documentsui` | 2 |
| VLC | `org.videolan.vlc` | 2 |
| Simple Draw Pro | `com.simplemobiletools.draw.pro` | 1 |
| System Settings | `com.android.settings` | 14 |

## Regenerating Benchmarks

These YAML files were manually converted from Android World's Python source. To regenerate:

**LLM Prompt:**
```
Convert Android World tasks from android_world/task_evals/single/{app}.py to Trailblaze .trail.yaml format.

For each task class, extract:
1. goal (from the goal property or template)
2. initialize_task() — setup operations (clear data, insert DB rows, create files)
3. is_successful() — validation logic

Create YAML with: config (id: android-world-benchmarks/{app}/{task}), setup tools, prompts (the goal), validation tools.

Setup tools: clearAppData, clearDirectory, createFileOnDevice, pushAssetToDevice, setClipboard, sendSmsToDevice, addContactToDevice, executeSqliteOnDevice (db paths below), runAdbShell.

Validation tools: assertAdbShellOutput, assertSqliteQuery, assertFileExistsOnDevice, assertFileContent.

Key constants:
- Device date: Oct 15 2023 15:34 UTC (use date -s 20231015.153400)
- Calendar DB: /data/data/com.simplemobiletools.calendar.pro/databases/events.db
- Expense DB: /data/data/com.arduia.expense/databases/accounting.db
- Recipe DB: /data/data/com.flauschcode.broccoli/databases/broccoli
- SMS DB: /data/data/com.android.providers.telephony/databases/mmssms.db
- Markor dir: /storage/emulated/0/Documents/Markor
- Music dir: /sdcard/Music

Skip tasks requiring PIL/Pillow image generation, OpenCV video generation, HTML/JS game generation, or phone calls.
```

## Tasks Requiring Audio Assets

6 tasks (retro_music/*, vlc/*) require MP3 files to be placed in `benchmarks/audio/` before running:

- `Sunset Dreams.mp3`, `Ocean Waves.mp3`, `Mountain Echo.mp3`, `City Lights.mp3`, `Forest Rain.mp3`

Any MP3 files with these names will work — the benchmarks only check playlist metadata, not audio content. These trails will fail at setup if the assets are missing.

## Omitted Tasks

8 tasks excluded due to programmatic media generation requirements:

- MarkorTranscribeReceipt, MarkorTranscribeVideo (image/video generation)
- BrowserMaze, BrowserMultiply, BrowserDraw (HTML/JS game generation)
- ExpenseAddMultipleFromGallery, RecipeAddMultipleRecipesFromImage (image generation)
- SimpleSmsSendAfterCall (disabled upstream due to flakiness)

## CLI Options

```bash
./trailblaze trail <path> [--device emulator-5554] [--verbose] [--headless]
```

## References

- Paper: https://arxiv.org/abs/2405.14573
- Source: https://github.com/google-research/android_world
