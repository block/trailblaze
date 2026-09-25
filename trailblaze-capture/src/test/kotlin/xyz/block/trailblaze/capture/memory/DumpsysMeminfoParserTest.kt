package xyz.block.trailblaze.capture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DumpsysMeminfoParserTest {

  @Test
  fun `reads the App Summary breakdown and totals from a real dump`() {
    val sample = assertNotNull(DumpsysMeminfoParser.parseAppSample(MeminfoFixtures.DUMPSYS_SETTINGS))
    assertEquals(36940, sample.totalPssKb)
    assertEquals(97060, sample.totalRssKb)
    assertEquals(728, sample.totalSwapPssKb)
    assertEquals(1112, sample.javaHeapKb)
    assertEquals(5168, sample.nativeHeapKb)
    assertEquals(24872, sample.codeKb)
    assertEquals(272, sample.stackKb)
    assertEquals(0, sample.graphicsKb)
    assertEquals(1484, sample.privateOtherKb)
    assertEquals(4032, sample.systemKb)
  }

  @Test
  fun `reads the Objects block, including both pairs on one line`() {
    val sample = assertNotNull(DumpsysMeminfoParser.parseAppSample(MeminfoFixtures.dumpsysWith(36940, activities = 4, views = 812)))
    assertEquals(812, sample.views)
    assertEquals(0, sample.viewRootImpls)
    assertEquals(3, sample.appContexts)
    assertEquals(4, sample.activities)
  }

  @Test
  fun `a dump without totals is no sample`() {
    // What `dumpsys meminfo <pid>` prints when the process exited between pidof and the dump.
    assertNull(DumpsysMeminfoParser.parseAppSample("No process found for: 17220\n"))
    assertNull(DumpsysMeminfoParser.parseAppSample(""))
  }

  @Test
  fun `a summary field the device omits reads as null rather than failing the sample`() {
    val withoutGraphics = MeminfoFixtures.DUMPSYS_SETTINGS.lines()
      .filterNot { it.contains("Graphics:") }
      .joinToString("\n")
    val sample = assertNotNull(DumpsysMeminfoParser.parseAppSample(withoutGraphics))
    assertNull(sample.graphicsKb)
    assertEquals(36940, sample.totalPssKb)
  }

  @Test
  fun `reads MemTotal and MemAvailable from proc meminfo`() {
    val device = assertNotNull(DumpsysMeminfoParser.parseDeviceSample(MeminfoFixtures.PROC_MEMINFO))
    assertEquals(2012188, device.memTotalKb)
    assertEquals(360660, device.memAvailableKb)
    assertNull(DumpsysMeminfoParser.parseDeviceSample("MemTotal:  1 kB\n"))
  }

  @Test
  fun `pidof output resolves to the first pid or nothing`() {
    assertEquals(17220, DumpsysMeminfoParser.parsePid("17220\n"))
    assertEquals(100, DumpsysMeminfoParser.parsePid("100 200"))
    assertNull(DumpsysMeminfoParser.parsePid(""))
    assertNull(DumpsysMeminfoParser.parsePid("   \n"))
    assertNull(DumpsysMeminfoParser.parsePid(null))
  }
}

class DumpsysMeminfoHeapAndLimitsTest {

  @Test
  fun `reads the heap size, alloc and free triples and derives heap used`() {
    val sample = assertNotNull(DumpsysMeminfoParser.parseAppSample(MeminfoFixtures.DUMPSYS_SETTINGS))
    assertEquals(2923, sample.javaHeapSizeKb)
    assertEquals(2193, sample.javaHeapAllocKb)
    assertEquals(730, sample.javaHeapFreeKb)
    assertEquals(2923 - 730, sample.javaHeapUsedKb, "size − free is what the VM has handed out")
    assertEquals(11612, sample.nativeHeapSizeKb)
    assertEquals(4779, sample.nativeHeapAllocKb)
    assertEquals(1243, sample.nativeHeapFreeKb)
    assertEquals(11612 - 1243, sample.nativeHeapUsedKb)
  }

  @Test
  fun `a dump without heap columns still parses without heap figures`() {
    val noHeap = MeminfoFixtures.DUMPSYS_SETTINGS.replace(
      "  Native Heap     5219     5168        8       85     6148    11612     4779     1243",
      "  Native Heap     5219     5168        8       85     6148",
    ).replace(
      "  Dalvik Heap      780      692       20      246     1696     2923     2193      730",
      "  Dalvik Heap      780      692       20      246     1696",
    )
    val sample = assertNotNull(DumpsysMeminfoParser.parseAppSample(noHeap))
    assertNull(sample.javaHeapUsedKb)
    assertNull(sample.nativeHeapUsedKb)
    assertEquals(36940, sample.totalPssKb)
  }

  @Test
  fun `limits come from the dalvik properties and the largeHeap package flag`() {
    val plain = assertNotNull(DumpsysMeminfoParser.parseLimits("192m\n", "512m\n", "    flags=[ DEBUGGABLE HAS_CODE ALLOW_BACKUP ]"))
    assertEquals(192 * 1024L, plain.heapGrowthLimitKb)
    assertEquals(512 * 1024L, plain.heapMaxKb)
    assertEquals(false, plain.largeHeap)
    assertEquals(192 * 1024L, plain.heapLimitKb)

    val large = assertNotNull(DumpsysMeminfoParser.parseLimits("192m", "512m", "flags=[ HAS_CODE LARGE_HEAP ]"))
    assertEquals(true, large.largeHeap)
    assertEquals(512 * 1024L, large.heapLimitKb)

    val unknownFlags = assertNotNull(DumpsysMeminfoParser.parseLimits("192m", "512m", null))
    assertNull(unknownFlags.largeHeap)
    assertEquals(192 * 1024L, unknownFlags.heapLimitKb, "without the flag, assume the ordinary limit")

    assertNull(DumpsysMeminfoParser.parseLimits("", null, null), "no property at all is no limit")
  }

  @Test
  fun `dalvik sizes accept k m g suffixes and bare bytes`() {
    assertEquals(196_608, DumpsysMeminfoParser.parseDalvikSize("192m"))
    assertEquals(1024, DumpsysMeminfoParser.parseDalvikSize("1M"))
    assertEquals(512, DumpsysMeminfoParser.parseDalvikSize("512k"))
    assertEquals(1_048_576, DumpsysMeminfoParser.parseDalvikSize("1g"))
    assertEquals(2, DumpsysMeminfoParser.parseDalvikSize("2048"))
    assertNull(DumpsysMeminfoParser.parseDalvikSize(""))
    assertNull(DumpsysMeminfoParser.parseDalvikSize("lots"))
  }
}
