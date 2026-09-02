package xyz.block.trailblaze.inprocess.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One element of a parsed binary AndroidManifest.xml. */
internal class BinaryXmlElement(
  val name: String,
  val attributes: List<BinaryXmlAttribute>,
  val children: MutableList<BinaryXmlElement> = mutableListOf(),
) {
  /** Value of the attribute in [namespace] named [name], or null when the element has none. */
  fun attr(namespace: String?, name: String): String? =
    attributes.firstOrNull { it.namespace == namespace && it.name == name }?.value

  /** Value of an `android:`-namespaced attribute. */
  fun androidAttr(name: String): String? = attr(ANDROID_NAMESPACE, name)

  /** Every descendant (and this element) whose tag is [tag], depth first. */
  fun descendants(tag: String): List<BinaryXmlElement> {
    val out = mutableListOf<BinaryXmlElement>()
    fun walk(e: BinaryXmlElement) {
      if (e.name == tag) out += e
      e.children.forEach(::walk)
    }
    walk(this)
    return out
  }

  /** Direct children whose tag is [tag]. */
  fun childrenNamed(tag: String): List<BinaryXmlElement> = children.filter { it.name == tag }
}

internal class BinaryXmlAttribute(
  val namespace: String?,
  val name: String,
  val value: String?,
)

internal const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

/**
 * Minimal reader for Android's binary XML (`AXML`) container, enough to answer the questions the app
 * fingerprint asks of a merged manifest: package name, `android:debuggable`, whether any activity
 * declares the LAUNCHER category, and every declared `<provider>`.
 *
 * Hand-rolled rather than delegating to `aapt2 dump xmltree` on purpose. `aapt2` is a per-OS native
 * binary shipped through an Android SDK; the whole point of the probe is that an adopter runs it on
 * a host with no SDK (docs/internal/inprocess-dogfooding-plan.md, item 4). The format is stable and
 * public (AOSP `ResourceTypes.h`) and only the chunk types below are needed.
 *
 * Not a general-purpose XML reader: styles, CDATA and typed-value formatting beyond what manifest
 * attributes use are out of scope, and resource *references* are rendered as `@0x…` rather than
 * resolved, since resolving one would mean parsing `resources.arsc` too.
 */
internal object AndroidBinaryXml {

  private const val RES_XML_TYPE = 0x0003
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val RES_XML_END_ELEMENT_TYPE = 0x0103

  private const val UTF8_FLAG = 1 shl 8

  /**
   * Offset of `ResXMLTree_attrExt` inside a start-element chunk: the 8-byte chunk header plus
   * `ResXMLTree_node`'s `lineNumber` and `comment`.
   */
  private const val ATTR_EXT_OFFSET = 16

  private const val TYPE_REFERENCE = 0x01
  private const val TYPE_STRING = 0x03
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_HEX = 0x11
  private const val TYPE_INT_BOOLEAN = 0x12

  /**
   * Parses [bytes] and returns the root element.
   *
   * @throws ApkReadException when the bytes are not a binary XML document, or are truncated.
   */
  fun parse(bytes: ByteArray): BinaryXmlElement {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 8) {
      throw ApkReadException("AndroidManifest.xml is ${bytes.size} bytes — too short to be binary XML.")
    }
    val fileType = buf.u16()
    if (fileType != RES_XML_TYPE) {
      throw ApkReadException(
        "AndroidManifest.xml does not start with the binary-XML magic (chunk type 0x%04x, expected 0x%04x). "
          .format(fileType, RES_XML_TYPE) +
          "A plain-text manifest means these bytes came from a source tree, not an APK.",
      )
    }
    val fileHeaderSize = buf.u16()
    buf.int // total size, unused: the array length is authoritative
    buf.position(fileHeaderSize)

    var strings: List<String> = emptyList()
    var root: BinaryXmlElement? = null
    val stack = ArrayDeque<BinaryXmlElement>()

    while (buf.remaining() >= 8) {
      val chunkStart = buf.position()
      val chunkType = buf.u16()
      buf.u16() // chunk header size, unused
      val chunkSize = buf.int
      if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) {
        throw ApkReadException(
          "AndroidManifest.xml is truncated: chunk 0x%04x at offset $chunkStart claims $chunkSize bytes."
            .format(chunkType),
        )
      }
      when (chunkType) {
        RES_STRING_POOL_TYPE -> strings = parseStringPool(buf, chunkStart)
        RES_XML_START_ELEMENT_TYPE -> {
          val element = parseStartElement(buf, chunkStart, strings)
          if (root == null) root = element
          stack.lastOrNull()?.children?.add(element)
          stack.addLast(element)
        }
        RES_XML_END_ELEMENT_TYPE -> stack.removeLastOrNull()
        RES_XML_START_NAMESPACE_TYPE -> Unit
        else -> Unit
      }
      buf.position(chunkStart + chunkSize)
    }
    return root
      ?: throw ApkReadException("AndroidManifest.xml carries no elements — nothing to fingerprint.")
  }

  private fun parseStringPool(buf: ByteBuffer, chunkStart: Int): List<String> {
    val stringCount = buf.int
    buf.int // style count, unused
    val flags = buf.int
    val stringsStart = buf.int
    buf.int // styles start, unused
    val utf8 = (flags and UTF8_FLAG) != 0
    val offsets = IntArray(stringCount) { buf.int }
    return offsets.map { offset ->
      readPooledString(buf, chunkStart + stringsStart + offset, utf8)
    }
  }

  private fun readPooledString(buf: ByteBuffer, at: Int, utf8: Boolean): String {
    var p = at
    return if (utf8) {
      // Two lengths, each 1 or 2 bytes: the UTF-16 length (which we ignore) then the byte length.
      p = skipUtf8Length(buf, p)
      var byteLen = buf.get(p).toInt() and 0xff
      p += 1
      if (byteLen and 0x80 != 0) {
        byteLen = ((byteLen and 0x7f) shl 8) or (buf.get(p).toInt() and 0xff)
        p += 1
      }
      val out = ByteArray(byteLen)
      for (i in 0 until byteLen) out[i] = buf.get(p + i)
      String(out, Charsets.UTF_8)
    } else {
      var charLen = buf.getShort(p).toInt() and 0xffff
      p += 2
      if (charLen and 0x8000 != 0) {
        charLen = ((charLen and 0x7fff) shl 16) or (buf.getShort(p).toInt() and 0xffff)
        p += 2
      }
      val chars = CharArray(charLen)
      for (i in 0 until charLen) chars[i] = buf.getShort(p + i * 2).toInt().toChar()
      String(chars)
    }
  }

  private fun skipUtf8Length(buf: ByteBuffer, at: Int): Int {
    val first = buf.get(at).toInt() and 0xff
    return if (first and 0x80 != 0) at + 2 else at + 1
  }

  private fun parseStartElement(
    buf: ByteBuffer,
    chunkStart: Int,
    strings: List<String>,
  ): BinaryXmlElement {
    // `ResXMLTree_node` is header(8) lineNumber(4) comment(4); `ResXMLTree_attrExt` follows at
    // [ATTR_EXT_OFFSET] with ns(4) name(4) attributeStart(2) attributeSize(2) attributeCount(2)
    // id/class/styleIndex(2 each).
    val nameIdx = buf.getInt(chunkStart + ATTR_EXT_OFFSET + 4)
    val attributeStart = buf.getShort(chunkStart + ATTR_EXT_OFFSET + 8).toInt() and 0xffff
    val attributeSize = buf.getShort(chunkStart + ATTR_EXT_OFFSET + 10).toInt() and 0xffff
    val attributeCount = buf.getShort(chunkStart + ATTR_EXT_OFFSET + 12).toInt() and 0xffff

    val attributes = (0 until attributeCount).map { i ->
      // `attributeStart` is relative to the start of `attrExt`, NOT to the chunk. Anchoring it at
      // the chunk instead reads every attribute record 16 bytes early, which lands the element's
      // own name in the namespace field and still parses — the failure is silently wrong values,
      // not an exception, which is why AndroidBinaryXmlTest asserts on named attributes.
      val base = chunkStart + ATTR_EXT_OFFSET + attributeStart + i * attributeSize
      val nsIdx = buf.getInt(base)
      val attrNameIdx = buf.getInt(base + 4)
      val rawValueIdx = buf.getInt(base + 8)
      val dataType = buf.get(base + 15).toInt() and 0xff
      val data = buf.getInt(base + 16)
      BinaryXmlAttribute(
        namespace = strings.getOrNull(nsIdx),
        name = strings.getOrNull(attrNameIdx) ?: "attr@$attrNameIdx",
        value = resolveValue(strings, rawValueIdx, dataType, data),
      )
    }
    return BinaryXmlElement(
      name = strings.getOrNull(nameIdx) ?: "element@$nameIdx",
      attributes = attributes,
    )
  }

  private fun resolveValue(
    strings: List<String>,
    rawValueIdx: Int,
    dataType: Int,
    data: Int,
  ): String? {
    if (rawValueIdx >= 0) return strings.getOrNull(rawValueIdx)
    return when (dataType) {
      TYPE_STRING -> strings.getOrNull(data)
      TYPE_INT_BOOLEAN -> (data != 0).toString()
      TYPE_INT_DEC -> data.toString()
      TYPE_INT_HEX -> "0x%x".format(data)
      // Left unresolved on purpose: resolving one means parsing resources.arsc, and no fingerprint
      // field reads a referenced value. Rendered so a reader can tell "a reference" from "absent".
      TYPE_REFERENCE -> "@0x%08x".format(data)
      else -> data.toString()
    }
  }

  private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
}
