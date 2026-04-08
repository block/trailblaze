package xyz.block.trailblaze.android

import android.os.Debug
import java.util.Locale
import xyz.block.trailblaze.util.Console

/**
 * Lightweight memory diagnostics for on-device Android execution.
 *
 * Logs ART heap and native heap usage to help diagnose OOM crashes
 * on memory-constrained devices (e.g. CI emulators with ~192 MB ART heap).
 */
object MemoryDiagnostics {

  /**
   * Best-effort memory dump for OOM catch blocks. Logs ART heap and native heap state,
   * then forces a GC to show how much memory was reclaimable. Never throws — if this
   * method itself hits an error (e.g. another OOM during string building), it swallows
   * the failure silently so the original error propagates cleanly.
   */
  fun dumpOnOom(error: OutOfMemoryError, context: String) {
    try {
      val rt = Runtime.getRuntime()
      val maxHeap = rt.maxMemory()
      val totalHeap = rt.totalMemory()
      val freeHeap = rt.freeMemory()
      val usedHeap = totalHeap - freeHeap
      val nativeHeap = Debug.getNativeHeapAllocatedSize()
      val nativeTotal = Debug.getNativeHeapSize()

      // Force a GC to see what's actually unreclaimable. This may cause a stop-the-world
      // pause, but since we're about to crash anyway, the diagnostic value is worth it.
      rt.gc()
      val postGcFree = rt.freeMemory()
      val postGcUsed = rt.totalMemory() - postGcFree

      Console.log(
        buildString {
          appendLine("╔══════════════════════════════════════════════════════")
          appendLine("║ OOM DIAGNOSTICS — $context")
          appendLine("╠══════════════════════════════════════════════════════")
          appendLine("║ Error: ${error.message}")
          appendLine("║")
          appendLine("║ ART Heap (at crash):")
          appendLine("║   Max:   ${mb(maxHeap)} MB")
          appendLine("║   Used:  ${mb(usedHeap)} MB (${percent(usedHeap, maxHeap)}%)")
          appendLine("║   Free:  ${mb(freeHeap)} MB")
          appendLine("║")
          appendLine("║ ART Heap (after emergency GC):")
          appendLine("║   Used:  ${mb(postGcUsed)} MB (${percent(postGcUsed, maxHeap)}%)")
          appendLine("║   Free:  ${mb(postGcFree)} MB")
          appendLine("║   Reclaimed: ${mb(usedHeap - postGcUsed)} MB")
          appendLine("║")
          appendLine("║ Native Heap:")
          appendLine("║   Allocated: ${mb(nativeHeap)} MB")
          appendLine("║   Total:     ${mb(nativeTotal)} MB")
          appendLine("╚══════════════════════════════════════════════════════")
        }
      )
    } catch (_: Throwable) {
      // Best-effort — if we can't even log, don't mask the original OOM
    }
  }

  private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))

  private fun percent(used: Long, max: Long): Int =
    if (max > 0) ((used * 100) / max).toInt() else 0
}
