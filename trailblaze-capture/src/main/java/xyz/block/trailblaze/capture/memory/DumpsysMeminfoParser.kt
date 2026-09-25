package xyz.block.trailblaze.capture.memory

import xyz.block.trailblaze.mcp.android.ondevice.rpc.AndroidMemoryReadCommands

/**
 * Pure parsers for the Android device outputs the memory probes read. No adb here, so the shapes
 * are unit-testable against captured fixtures.
 */
object DumpsysMeminfoParser {

  /**
   * Parses `dumpsys meminfo <pid>` (the full, unabridged dump). Returns null when the output has
   * no "TOTAL PSS" line — the process was gone by the time dumpsys ran, or the shell failed.
   *
   * Reads the "App Summary" block for the PSS breakdown, the "Dalvik Heap" / "Native Heap" rows of
   * the main table for heap size / alloc / free, and the "Objects" block for the leak counters. All
   * three have been stable since Android 8; a field the device omits (older builds lack `Graphics`,
   * some lack `TOTAL RSS`) reads as null rather than failing the sample.
   */
  fun parseAppSample(output: String): AppMemorySample? {
    val totals = TOTAL_LINE.find(output) ?: return null
    val summary = mutableMapOf<String, Long>()
    var inSummary = false
    var inObjects = false
    val objects = mutableMapOf<String, Int>()
    var dalvikHeap: HeapTriple? = null
    var nativeHeap: HeapTriple? = null
    for (rawLine in output.lineSequence()) {
      val line = rawLine.trim()
      when {
        line == "App Summary" -> { inSummary = true; inObjects = false; continue }
        line == "Objects" -> { inSummary = false; inObjects = true; continue }
        line.isEmpty() -> continue
        // Another block header ("SQL", "DATABASES", "Asset Allocations", …) ends the one we were in.
        // Column headers ("Pss(KB)   Rss(KB)") and rules ("------") are not headers.
        BLOCK_HEADER.matches(line) && (inSummary || inObjects) -> { inSummary = false; inObjects = false; continue }
      }
      if (inSummary) {
        SUMMARY_LINE.find(line)?.let { m -> summary[m.groupValues[1].trim()] = m.groupValues[2].toLong() }
      } else if (inObjects) {
        // Two "Label: value" pairs per line, e.g. "Views:  12   ViewRootImpl:  1".
        OBJECT_PAIR.findAll(line).forEach { m -> objects[m.groupValues[1].trim()] = m.groupValues[2].toInt() }
      } else {
        HEAP_ROW.find(line)?.let { m ->
          val triple = heapTriple(m.groupValues[2]) ?: return@let
          when (m.groupValues[1]) {
            "Dalvik Heap" -> dalvikHeap = triple
            "Native Heap" -> nativeHeap = triple
          }
        }
      }
    }
    return AppMemorySample(
      totalPssKb = totals.groupValues[1].toLong(),
      totalRssKb = TOTAL_RSS.find(output)?.groupValues?.get(1)?.toLong(),
      totalSwapPssKb = TOTAL_SWAP_PSS.find(output)?.groupValues?.get(1)?.toLong(),
      javaHeapKb = summary["Java Heap"],
      nativeHeapKb = summary["Native Heap"],
      codeKb = summary["Code"],
      stackKb = summary["Stack"],
      graphicsKb = summary["Graphics"],
      privateOtherKb = summary["Private Other"],
      systemKb = summary["System"],
      views = objects["Views"],
      viewRootImpls = objects["ViewRootImpl"],
      appContexts = objects["AppContexts"],
      activities = objects["Activities"],
      javaHeapSizeKb = dalvikHeap?.sizeKb,
      javaHeapAllocKb = dalvikHeap?.allocKb,
      javaHeapFreeKb = dalvikHeap?.freeKb,
      nativeHeapSizeKb = nativeHeap?.sizeKb,
      nativeHeapAllocKb = nativeHeap?.allocKb,
      nativeHeapFreeKb = nativeHeap?.freeKb,
    )
  }

  private data class HeapTriple(val sizeKb: Long, val allocKb: Long, val freeKb: Long)

  /**
   * The "Dalvik Heap" / "Native Heap" rows end in `Heap Size, Heap Alloc, Heap Free`. Android 8–9
   * print 7 numbers (no Rss column), 10+ print 8; anything shorter is a row without heap columns.
   */
  private fun heapTriple(numbers: String): HeapTriple? {
    val values = numbers.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
    if (values.size < 7) return null
    return HeapTriple(sizeKb = values[values.size - 3], allocKb = values[values.size - 2], freeKb = values[values.size - 1])
  }

  /** Parses `/proc/meminfo`. Null unless both `MemTotal` and `MemAvailable` are present. */
  fun parseDeviceSample(output: String): DeviceMemorySample? {
    var total: Long? = null
    var available: Long? = null
    for (line in output.lineSequence()) {
      val m = PROC_LINE.find(line) ?: continue
      when (m.groupValues[1]) {
        "MemTotal" -> total = m.groupValues[2].toLong()
        "MemAvailable" -> available = m.groupValues[2].toLong()
      }
      if (total != null && available != null) break
    }
    val t = total ?: return null
    val a = available ?: return null
    return DeviceMemorySample(memTotalKb = t, memAvailableKb = a)
  }

  /** First pid from `pidof <package>` output; null when the process is not running. */
  fun parsePid(output: String?): Int? = AndroidMemoryReadCommands.firstPid(output)

  /**
   * Builds the heap limits from `getprop dalvik.vm.heapgrowthlimit`, `getprop dalvik.vm.heapsize`
   * (both `<n>m`, occasionally `<n>k`) and the `flags=[ … ]` line of `dumpsys package <app>`, where
   * `LARGE_HEAP` marks a manifest `android:largeHeap="true"`. Null when neither property parsed.
   */
  fun parseLimits(heapGrowthLimit: String?, heapSize: String?, packageFlagsLine: String?): AppMemoryLimits? {
    val growth = parseDalvikSize(heapGrowthLimit)
    val max = parseDalvikSize(heapSize)
    if (growth == null && max == null) return null
    val largeHeap = packageFlagsLine?.let { line ->
      FLAGS_LINE.find(line)?.groupValues?.get(1)?.split(Regex("\\s+"))?.contains("LARGE_HEAP")
    }
    return AppMemoryLimits(heapGrowthLimitKb = growth, heapMaxKb = max, largeHeap = largeHeap)
  }

  /** `192m` → 196608 kB; `512k` → 512; a bare number is bytes. */
  fun parseDalvikSize(value: String?): Long? {
    val m = DALVIK_SIZE.find(value?.trim() ?: return null) ?: return null
    val n = m.groupValues[1].toLongOrNull() ?: return null
    return when (m.groupValues[2].lowercase()) {
      "g" -> n * 1024 * 1024
      "m" -> n * 1024
      "k" -> n
      else -> n / 1024
    }
  }

  private val BLOCK_HEADER = Regex("""^[A-Za-z][A-Za-z ]*$""")
  private val TOTAL_LINE = Regex("""TOTAL PSS:\s+(\d+)""")
  private val TOTAL_RSS = Regex("""TOTAL RSS:\s+(\d+)""")
  private val TOTAL_SWAP_PSS = Regex("""TOTAL SWAP PSS:\s+(\d+)""")
  // "Java Heap:     1112       11780" — the first number is PSS, the optional second is RSS.
  private val SUMMARY_LINE = Regex("""^([A-Za-z ]+):\s+(\d+)""")
  private val OBJECT_PAIR = Regex("""([A-Za-z ]+):\s+(\d+)""")
  private val PROC_LINE = Regex("""^(\w+):\s+(\d+)\s*kB""")
  private val HEAP_ROW = Regex("""^(Dalvik Heap|Native Heap)\s+((?:\d+\s*)+)$""")
  private val FLAGS_LINE = Regex("""\bflags=\[([^\]]*)\]""")
  private val DALVIK_SIZE = Regex("""^(\d+)\s*([kKmMgG]?)$""")
}
