package xyz.block.trailblaze.capture.memory

/** Verbatim `dumpsys meminfo` / `/proc/meminfo` output from an API 33 arm64 emulator. */
internal object MeminfoFixtures {
  val DUMPSYS_SETTINGS = """
    Applications Memory Usage (in Kilobytes):
    Uptime: 116762129 Realtime: 116762129

    ** MEMINFO in pid 17220 [com.android.settings] **
                       Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                     Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                    ------   ------   ------   ------   ------   ------   ------   ------
      Native Heap     5219     5168        8       85     6148    11612     4779     1243
      Dalvik Heap      780      692       20      246     1696     2923     2193      730
     Dalvik Other     1028     1016        0       65     1708
            Stack      272      272        0        0      280
           Ashmem       22        0        0        0      372
        Other dev       18        0       16        0      280
         .so mmap      836      132       80       29    16824
        .jar mmap     2535        0      972        0    23276
        .apk mmap    23343        0    23248        0    23916
        .dex mmap      380       20      332        0      984
        .oat mmap      498        0       72        0     9956
        .art mmap      801      372       48      302    10084
       Other mmap       39        8        4        0      768
          Unknown      441      424        4        1      768
            TOTAL    36940     8104    24804      728    97060    14535     6972     1973

     App Summary
                           Pss(KB)                        Rss(KB)
                            ------                         ------
               Java Heap:     1112                          11780
             Native Heap:     5168                           6148
                    Code:    24872                          75008
                   Stack:      272                            280
                Graphics:        0                              0
           Private Other:     1484
                  System:     4032
                 Unknown:                                    3844

               TOTAL PSS:    36940            TOTAL RSS:    97060       TOTAL SWAP PSS:      728

     Objects
                   Views:        0         ViewRootImpl:        0
             AppContexts:        3           Activities:        0
                  Assets:       18        AssetManagers:        0
           Local Binders:       21        Proxy Binders:       32
           Parcel memory:        3         Parcel count:       14
        Death Recipients:        0      OpenSSL Sockets:        0
                WebViews:        0

     SQL
             MEMORY_USED:        0
      PAGECACHE_OVERFLOW:        0          MALLOC_SIZE:        0

  """.trimIndent()

  val PROC_MEMINFO = """
    MemTotal:        2012188 kB
    MemFree:          138292 kB
    MemAvailable:     360660 kB
    Buffers:           10816 kB
    Cached:           509832 kB
  """.trimIndent()

  /** The Dalvik Heap row's free column in [DUMPSYS_SETTINGS]; heap used = size − this. */
  const val DALVIK_HEAP_FREE_KB: Long = 730

  /**
   * [DUMPSYS_SETTINGS] with the PSS total, the Dalvik heap size, and the activity count swapped
   * for the given values.
   */
  fun dumpsysWith(totalPssKb: Long, activities: Int = 0, views: Int = 0, heapSizeKb: Long = 2923): String =
    DUMPSYS_SETTINGS
      .replace("TOTAL PSS:    36940", "TOTAL PSS:    $totalPssKb")
      .replace("1696     2923     2193      730", "1696     ${heapSizeKb.toString().padStart(4)}     2193      730")
      .replace("Activities:        0", "Activities:        $activities")
      .replace("Views:        0", "Views:        $views")

  fun procWith(availableKb: Long): String =
    PROC_MEMINFO.replace("MemAvailable:     360660 kB", "MemAvailable:     $availableKb kB")
}
