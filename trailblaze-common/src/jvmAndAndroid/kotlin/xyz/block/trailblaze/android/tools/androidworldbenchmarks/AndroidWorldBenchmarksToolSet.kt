package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import xyz.block.trailblaze.mobile.tools.ClearAppDataTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetClass

/**
 * Tool set for Android benchmark setup and validation.
 *
 * These tools are designed for use in trail.yaml `- tools:` blocks (before and
 * after `- prompts:`) to programmatically set up device state before a benchmark
 * task and validate the result afterward.
 *
 * None of these tools are exposed to the LLM — they are YAML-only (`isForLlm = false`).
 *
 * Includes:
 * - [AndroidWorldBenchmarksRunAdbShellTrailblazeTool] — Run arbitrary adb shell commands
 * - [ClearAppDataTrailblazeTool] — Clear all data for an app package
 * - [AndroidWorldBenchmarksClearDirectoryTrailblazeTool] — Clear contents of a directory on the device
 * - [AndroidWorldBenchmarksCreateFileOnDeviceTrailblazeTool] — Create text files on the device
 * - [AndroidWorldBenchmarksPushAssetToDeviceTrailblazeTool] — Copy binary assets from the test APK to the device
 * - [AndroidWorldBenchmarksSendSmsToDeviceTrailblazeTool] — Simulate incoming SMS messages
 * - [AndroidWorldBenchmarksAddContactToDeviceTrailblazeTool] — Add a contact to the device
 * - [AndroidWorldBenchmarksExecuteSqliteOnDeviceTrailblazeTool] — Execute SQL statements on device databases
 * - [AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool] — Assert adb shell command output for validation
 * - [AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool] — Assert SQLite query results for validation
 * - [AndroidWorldBenchmarksAssertFileExistsOnDeviceTrailblazeTool] — Assert file exists (or not) on the device
 * - [AndroidWorldBenchmarksAssertFileContentTrailblazeTool] — Assert file content matches expected value
 */
@TrailblazeToolSetClass("Android Benchmark Tools")
object AndroidWorldBenchmarksToolSet : TrailblazeToolSet(
  toolClasses = setOf(
    // Setup tools
    AndroidWorldBenchmarksRunAdbShellTrailblazeTool::class,
    ClearAppDataTrailblazeTool::class,
    AndroidWorldBenchmarksClearDirectoryTrailblazeTool::class,
    AndroidWorldBenchmarksCreateFileOnDeviceTrailblazeTool::class,
    AndroidWorldBenchmarksPushAssetToDeviceTrailblazeTool::class,
    AndroidWorldBenchmarksSendSmsToDeviceTrailblazeTool::class,
    AndroidWorldBenchmarksAddContactToDeviceTrailblazeTool::class,
    AndroidWorldBenchmarksExecuteSqliteOnDeviceTrailblazeTool::class,
    // Validation tools
    AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool::class,
    AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool::class,
    AndroidWorldBenchmarksAssertFileExistsOnDeviceTrailblazeTool::class,
    AndroidWorldBenchmarksAssertFileContentTrailblazeTool::class,
  ),
)
