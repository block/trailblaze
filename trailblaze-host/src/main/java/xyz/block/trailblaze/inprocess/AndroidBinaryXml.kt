package xyz.block.trailblaze.inprocess

/**
 * The slice of Android's compiled-XML (binary AXML) format `trailblaze inprocess make-test-apk`
 * needs: read a few attributes out of a manifest, and repoint exactly one of them at a new string.
 *
 * A prebuilt test APK's `AndroidManifest.xml` is already compiled, and the one thing that has to
 * change per adopter — `<instrumentation android:targetPackage>` — is a string reference. Editing it
 * here keeps the whole retargeting path pure JVM: no `aapt2`, no Android SDK, no Gradle. `aapt2`
 * would work, but it is a per-OS native binary, which is the dependency this exists to avoid.
 *
 * Layout, all little-endian:
 * ```
 * file:  ResChunk_header { u16 type=0x0003, u16 headerSize=8, u32 size }, then a chunk sequence
 * pool:  ResChunk_header { type=0x0001, headerSize=0x1c, size }
 *        u32 stringCount, u32 styleCount, u32 flags, u32 stringsStart, u32 stylesStart
 *        u32[stringCount] offsets (relative to chunkStart + stringsStart)
 *        u32[styleCount]  offsets
 *        string data
 * node:  ResChunk_header, u32 lineNumber, u32 comment                            (16 bytes)
 * START_ELEMENT (0x0102) body: u32 ns, u32 name, u16 attrStart, u16 attrSize, u16 attrCount,
 *                              u16 idIndex, u16 classIndex, u16 styleIndex
 * attribute: u32 ns, u32 name, u32 rawValue, Res_value { u16 size, u8 res0, u8 dataType, u32 data }
 * ```
 */
object AndroidBinaryXml {

  private const val RES_XML_TYPE = 0x0003
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val UTF8_FLAG = 0x0100
  private const val TYPE_STRING = 0x03
  private const val TYPE_INT_BOOLEAN = 0x12

  private const val POOL_HEADER_SIZE = 28

  /**
   * Rewrites `<instrumentation android:targetPackage>` to [newTargetPackage] and returns the new
   * manifest bytes. The value may be any length — that is the whole point, since a real adopter's
   * package has nothing to do with the placeholder the shell was built against.
   *
   * The new value is APPENDED to the string pool and the attribute repointed at it, never written
   * over the entry it currently references: the pool deduplicates by content, so an in-place edit
   * silently rewrites every OTHER reference to the same string. AGP's own output is a near-miss —
   * it emits `android:label="Tests for <package>"` on the same element, a distinct entry — and a
   * search-and-replace over the pool bytes would corrupt it. Appending is correct whether the pool
   * aliases or not, and it is one code path instead of two.
   */
  fun stampInstrumentationTargetPackage(axml: ByteArray, newTargetPackage: String): ByteArray {
    require(newTargetPackage.isNotBlank()) { "target package must not be blank" }
    val pool = readFileStringPool(axml)
    val attr = findAttributes(axml, pool, element = "instrumentation", attribute = "targetPackage")
      .singleOrNull()
      ?: error(
        "This APK's manifest does not have exactly one <instrumentation android:targetPackage>. " +
          "Only a `com.android.test` module's APK carries one, and that is the only kind of APK " +
          "that can be retargeted at another app.",
      )
    require(attr.dataType == TYPE_STRING) {
      "android:targetPackage is not a string value (dataType=0x%02x)".format(attr.dataType)
    }
    return appendPoolStringAndRepoint(axml, pool, newTargetPackage, listOf(attr))
  }

  /**
   * `<instrumentation android:targetPackage>` — the app a test APK attaches to, or null when the
   * manifest declares no instrumentation (i.e. it is an app APK, not a test APK).
   */
  fun readInstrumentationTargetPackage(axml: ByteArray): String? {
    val pool = readFileStringPool(axml)
    val attr = findAttributes(axml, pool, element = "instrumentation", attribute = "targetPackage")
      .singleOrNull() ?: return null
    return pool.strings[axml.u32(attr.rawValueOffset)]
  }

  /** The `package` attribute on `<manifest>` — an APK's own applicationId. */
  fun readPackageName(axml: ByteArray): String {
    val pool = readFileStringPool(axml)
    val attr = findAttributes(axml, pool, element = "manifest", attribute = "package")
      .singleOrNull()
      ?: error("manifest has no single <manifest package=...> attribute")
    return pool.strings[axml.u32(attr.rawValueOffset)]
  }

  /**
   * `<application android:debuggable>`, defaulting to false — AAPT omits the attribute entirely on a
   * release build rather than writing `false`.
   */
  fun readApplicationDebuggable(axml: ByteArray): Boolean {
    val pool = readFileStringPool(axml)
    val attr = findAttributes(axml, pool, element = "application", attribute = "debuggable")
      .firstOrNull() ?: return false
    require(attr.dataType == TYPE_INT_BOOLEAN) {
      "android:debuggable is not a boolean value (dataType=0x%02x)".format(attr.dataType)
    }
    return axml.u32(attr.dataOffset) != 0
  }

  // -------------------------------------------------------------------------------------------

  private class StringPool(
    val chunkStart: Int,
    val chunkSize: Int,
    val count: Int,
    val styleCount: Int,
    val flags: Int,
    val stringsStart: Int,
    val offsets: IntArray,
    /** Encoded byte length of each entry, so a writer can find where the data region really ends. */
    val encodedLengths: IntArray,
    val strings: List<String>,
  ) {
    val isUtf8 get() = (flags and UTF8_FLAG) != 0
  }

  /** Where an attribute's two string references live in the file, and what kind of value it holds. */
  private class AttributeRef(val rawValueOffset: Int, val dataOffset: Int, val dataType: Int)

  private fun readFileStringPool(axml: ByteArray): StringPool {
    require(axml.size >= 8 && axml.u16(0) == RES_XML_TYPE) {
      "not a binary AndroidManifest.xml (expected chunk type 0x0003)"
    }
    val declaredSize = axml.u32(4)
    require(declaredSize == axml.size) {
      "manifest header declares $declaredSize bytes but the entry is ${axml.size}"
    }
    return readStringPool(axml, chunkStart = 8)
  }

  private fun readStringPool(bytes: ByteArray, chunkStart: Int): StringPool {
    require(bytes.u16(chunkStart) == RES_STRING_POOL_TYPE) {
      "expected a string pool at offset $chunkStart"
    }
    val chunkSize = bytes.u32(chunkStart + 4)
    val count = bytes.u32(chunkStart + 8)
    val styleCount = bytes.u32(chunkStart + 12)
    val flags = bytes.u32(chunkStart + 16)
    val stringsStart = bytes.u32(chunkStart + 20)
    val offsets = IntArray(count) { bytes.u32(chunkStart + POOL_HEADER_SIZE + it * 4) }
    val lengths = IntArray(count)
    val utf8 = (flags and UTF8_FLAG) != 0
    val strings = ArrayList<String>(count)
    for (i in 0 until count) {
      val start = chunkStart + stringsStart + offsets[i]
      var p = start
      if (utf8) {
        // A UTF-8 entry carries TWO lengths — character count, then byte count — each in a 1- or
        // 2-byte form flagged by the high bit, then the bytes and a NUL.
        var chars = bytes.u8(p); p++
        if (chars and 0x80 != 0) {
          chars = ((chars and 0x7F) shl 8) or bytes.u8(p); p++
        }
        var byteLen = bytes.u8(p); p++
        if (byteLen and 0x80 != 0) {
          byteLen = ((byteLen and 0x7F) shl 8) or bytes.u8(p); p++
        }
        strings += String(bytes, p, byteLen, Charsets.UTF_8)
        lengths[i] = (p + byteLen + 1) - start
      } else {
        var units = bytes.u16(p); p += 2
        if (units and 0x8000 != 0) {
          units = ((units and 0x7FFF) shl 16) or bytes.u16(p); p += 2
        }
        strings += String(bytes, p, units * 2, Charsets.UTF_16LE)
        lengths[i] = (p + units * 2 + 2) - start
      }
    }
    return StringPool(
      chunkStart = chunkStart,
      chunkSize = chunkSize,
      count = count,
      styleCount = styleCount,
      flags = flags,
      stringsStart = stringsStart,
      offsets = offsets,
      encodedLengths = lengths,
      strings = strings,
    )
  }

  private fun encodePoolString(pool: StringPool, s: String): ByteArray {
    if (pool.isUtf8) {
      val utf8 = s.toByteArray(Charsets.UTF_8)
      // UTF-16 code UNITS, not code points: AOSP's ResStringPool writes the length of the string as
      // it would be in UTF-16, and a surrogate pair counts twice there. codePointCount would
      // under-report any astral character by one and skew every reader that trusts the field.
      val chars = s.length
      val out = ArrayList<Byte>(utf8.size + 5)
      out += encodedUtf8Length(chars)
      out += encodedUtf8Length(utf8.size)
      utf8.forEach { out += it }
      out += 0
      return out.toByteArray()
    }
    val units = s.toByteArray(Charsets.UTF_16LE)
    val count = units.size / 2
    require(count < 0x8000) {
      "pool string needs the extended UTF-16 length form, which no manifest attribute value does: $s"
    }
    val out = ByteArray(2 + units.size + 2)
    out.putU16(0, count)
    units.copyInto(out, 2)
    return out
  }

  /** The 1- or 2-byte high-bit-flagged length form a UTF-8 pool entry uses. */
  private fun encodedUtf8Length(n: Int): List<Byte> {
    require(n < 0x8000) { "UTF-8 pool length $n exceeds the 15-bit encodable range" }
    return if (n < 0x80) {
      listOf(n.toByte())
    } else {
      listOf((((n shr 8) and 0x7F) or 0x80).toByte(), (n and 0xFF).toByte())
    }
  }

  private fun findAttributes(
    bytes: ByteArray,
    pool: StringPool,
    element: String,
    attribute: String,
  ): List<AttributeRef> {
    val found = ArrayList<AttributeRef>()
    var pos = 8
    val fileEnd = bytes.u32(4)
    while (pos + 8 <= fileEnd) {
      val type = bytes.u16(pos)
      val size = bytes.u32(pos + 4)
      require(size > 0) { "zero-size chunk at offset $pos — manifest is malformed" }
      if (type == RES_XML_START_ELEMENT_TYPE) {
        val body = pos + 16
        val nameIdx = bytes.u32(body + 4)
        if (nameIdx in 0 until pool.count && pool.strings[nameIdx] == element) {
          val attrStart = bytes.u16(body + 8)
          val attrSize = bytes.u16(body + 10)
          val attrCount = bytes.u16(body + 12)
          for (i in 0 until attrCount) {
            val a = body + attrStart + i * attrSize
            val attrNameIdx = bytes.u32(a + 4)
            if (attrNameIdx in 0 until pool.count && pool.strings[attrNameIdx] == attribute) {
              found += AttributeRef(
                rawValueOffset = a + 8,
                dataOffset = a + 16,
                dataType = bytes.u8(a + 15),
              )
            }
          }
        }
      }
      pos += size
    }
    return found
  }

  /**
   * Appends [value] to the string pool and points every attribute in [attributes] at the new index.
   *
   * What has to be recomputed when the pool grows by one entry: the chunk `size`, `stringCount`,
   * `stringsStart` (the offset array gained a `u32`), the appended entry's offset, and the file
   * header's `size`. The EXISTING offsets do not change — they are relative to `stringsStart` and
   * the data region is copied verbatim.
   *
   * Appending is also what keeps `RES_XML_RESOURCE_MAP_TYPE` valid: that chunk maps pool indices
   * `0..n-1` to attribute resource ids positionally, so a new entry at the end leaves it alone
   * while an insertion anywhere else would shift it.
   */
  private fun appendPoolStringAndRepoint(
    axml: ByteArray,
    pool: StringPool,
    value: String,
    attributes: List<AttributeRef>,
  ): ByteArray {
    require(pool.styleCount == 0) {
      "this manifest has a styled string pool (styleCount=${pool.styleCount}); appending to it " +
        "would need stylesStart adjusted too. No AGP-generated manifest has one, so rather than " +
        "guess at the layout this refuses — file a bug with the APK."
    }
    val newIndex = pool.count
    val encoded = encodePoolString(pool, value)

    // The chunk's declared size includes up to 3 bytes of alignment padding after the last string.
    // Take the data region as the furthest entry END, not `chunkSize - stringsStart`, or the new
    // entry lands after a padding gap and the file's own arithmetic stops adding up.
    // maxOf throws on an empty range, and a pool with no strings is a legal (if odd) chunk.
    val dataEnd = if (pool.count == 0) {
      0
    } else {
      (0 until pool.count).maxOf { pool.offsets[it] + pool.encodedLengths[it] }
    }
    val dataRegion = axml.copyOfRange(
      pool.chunkStart + pool.stringsStart,
      pool.chunkStart + pool.stringsStart + dataEnd,
    )

    val newStringsStart = POOL_HEADER_SIZE + (pool.count + 1) * 4
    val unpadded = newStringsStart + dataRegion.size + encoded.size
    val newPoolSize = (unpadded + 3) and 3.inv()
    val newPool = ByteArray(newPoolSize)
    newPool.putU16(0, RES_STRING_POOL_TYPE)
    newPool.putU16(2, POOL_HEADER_SIZE)
    newPool.putU32(4, newPoolSize)
    newPool.putU32(8, pool.count + 1)
    newPool.putU32(12, 0)
    newPool.putU32(16, pool.flags)
    newPool.putU32(20, newStringsStart)
    newPool.putU32(24, 0)
    for (i in 0 until pool.count) newPool.putU32(POOL_HEADER_SIZE + i * 4, pool.offsets[i])
    newPool.putU32(POOL_HEADER_SIZE + pool.count * 4, dataEnd)
    dataRegion.copyInto(newPool, newStringsStart)
    encoded.copyInto(newPool, newStringsStart + dataRegion.size)

    // Everything after the pool keeps its length — the patch below only overwrites u32s — so the
    // tail can be copied once and edited in place.
    val tailBase = pool.chunkStart + pool.chunkSize
    val tail = axml.copyOfRange(tailBase, axml.size)
    for (attr in attributes) {
      tail.putU32(attr.rawValueOffset - tailBase, newIndex)
      tail.putU32(attr.dataOffset - tailBase, newIndex)
    }

    val out = ByteArray(8 + newPool.size + tail.size)
    out.putU16(0, RES_XML_TYPE)
    out.putU16(2, 8)
    out.putU32(4, out.size)
    newPool.copyInto(out, 8)
    tail.copyInto(out, 8 + newPool.size)
    return out
  }

  private fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

  private fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

  private fun ByteArray.u32(o: Int): Int =
    u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

  private fun ByteArray.putU16(o: Int, v: Int) {
    this[o] = (v and 0xFF).toByte()
    this[o + 1] = ((v ushr 8) and 0xFF).toByte()
  }

  private fun ByteArray.putU32(o: Int, v: Int) {
    this[o] = (v and 0xFF).toByte()
    this[o + 1] = ((v ushr 8) and 0xFF).toByte()
    this[o + 2] = ((v ushr 16) and 0xFF).toByte()
    this[o + 3] = ((v ushr 24) and 0xFF).toByte()
  }
}
