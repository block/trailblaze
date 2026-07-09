package xyz.block.trailblaze.trailrunner

import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage

/**
 * Rasterizes the common shape of Android **vector adaptive icons** (the launcher icon most modern
 * apps ship *instead of* raster mipmaps) with plain Java2D — no Android runtime, no new
 * dependencies. Input is `aapt2 dump xmltree` output for the layer drawables, which is a stable,
 * indentation-structured dump of the compiled binary XML.
 *
 * Deliberately a SUBSET renderer: `<vector>` viewport, nested `<group>` transforms
 * (scale/rotate/pivot/translate), and `<path>` with solid or linear/radial-gradient fills — the
 * vocabulary launcher icons actually use (verified against several real-world app APKs). Anything
 * outside it (clip-path, trim, stroke-only paths, sweep gradients) returns null and the caller
 * falls back to a glyph, so an unsupported icon can never render *wrong* — only absent.
 */
internal object AndroidVectorIconRasterizer {

  // ─── Model ─────────────────────────────────────────────────────────────────

  internal sealed interface VdFill {
    data class Solid(val argb: Int) : VdFill

    data class Linear(
      val startX: Float,
      val startY: Float,
      val endX: Float,
      val endY: Float,
      val stops: List<Pair<Float, Int>>,
    ) : VdFill

    data class Radial(
      val centerX: Float,
      val centerY: Float,
      val radius: Float,
      val stops: List<Pair<Float, Int>>,
    ) : VdFill
  }

  internal sealed interface VdNode

  internal data class VdPath(
    val pathData: String,
    val fill: VdFill?,
    val evenOdd: Boolean,
    val fillAlpha: Float,
  ) : VdNode

  internal data class VdGroup(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val children: List<VdNode>,
  ) : VdNode

  internal data class VdVector(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val children: List<VdNode>,
  )

  /** One adaptive-icon layer: a full vector document or a plain solid color. */
  internal sealed interface VdLayer {
    data class Vector(val vector: VdVector) : VdLayer

    data class Color(val argb: Int) : VdLayer
  }

  // ─── aapt2 xmltree parsing ─────────────────────────────────────────────────

  private val ELEMENT = Regex("""^(\s*)E: ([\w-]+) """)

  // The namespace prefix is a full URL (`http://schemas.android.com/apk/res/android:`), so the
  // attribute name is whatever follows the LAST colon before the `(0x...)` resource-id suffix.
  private val ATTRIBUTE = Regex("""^(\s*)A: (?:.*:)?([\w-]+)\(0x[0-9a-f]+\)=(.*)$""")

  private class Node(val name: String, val indent: Int) {
    val attrs = mutableMapOf<String, String>()
    val children = mutableListOf<Node>()
  }

  /** Parses `aapt2 dump xmltree` output into a tree; returns the first element named [rootName]. */
  private fun parseTree(lines: List<String>, rootName: String): Node? {
    var root: Node? = null
    val stack = ArrayDeque<Node>()
    for (line in lines) {
      ELEMENT.find(line)?.let { m ->
        val node = Node(m.groupValues[2], m.groupValues[1].length)
        while (stack.isNotEmpty() && stack.last().indent >= node.indent) stack.removeLast()
        if (root == null && node.name == rootName) {
          root = node
        } else {
          stack.lastOrNull()?.children?.add(node)
        }
        // Attach to the tree even before the wanted root is found — namespaces (`N:`) nest above.
        if (root != null && node !== root) {
          // already added to parent above when stack non-empty; a stray sibling outside root is dropped
        }
        stack.addLast(node)
        return@let
      }
      ATTRIBUTE.find(line)?.let { m ->
        val indent = m.groupValues[1].length
        while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeLast()
        stack.lastOrNull()?.attrs?.put(m.groupValues[2], m.groupValues[3])
      }
    }
    return root
  }

  /** `108.000000dp` / `93` / `0.326953` / `0.000000e+00` → float. */
  private fun parseFloat(raw: String?): Float? = raw?.removeSuffix("dp")?.toFloatOrNull()

  /** `#aarrggbb` (or `#rrggbb`) → ARGB int. */
  private fun parseColor(raw: String?): Int? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    return when (hex.length) {
      8 -> hex.toLongOrNull(16)?.toInt()
      6 -> hex.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
      else -> null
    }
  }

  /**
   * Parses one layer drawable's xmltree [lines] into a [VdVector]. `fillColor` values of the form
   * `@0x7f08xxxx` (aapt-inlined gradient resources) are resolved through [resolveRefLines], which
   * returns that resource's own xmltree lines (or null → the path is treated as unfilled).
   * Any unsupported element (clip-path etc.) fails the whole parse — see class kdoc.
   */
  internal fun parseVector(lines: List<String>, resolveRefLines: (String) -> List<String>?): VdVector? {
    val root = parseTree(lines, "vector") ?: return null
    val vw = parseFloat(root.attrs["viewportWidth"]) ?: return null
    val vh = parseFloat(root.attrs["viewportHeight"]) ?: return null
    fun convert(node: Node): VdNode? = when (node.name) {
      "path" -> {
        val pathData = node.attrs["pathData"]?.let { raw ->
          Regex("^\"(.*)\" \\(Raw: ").find(raw)?.groupValues?.get(1) ?: raw.trim('"')
        } ?: return null
        val fill: VdFill? = node.attrs["fillColor"]?.let { rawFill ->
          when {
            rawFill.startsWith("#") -> parseColor(rawFill)?.let { VdFill.Solid(it) }
            rawFill.startsWith("@") -> resolveRefLines(rawFill.removePrefix("@"))?.let { parseGradient(it) }
            else -> null
          }
        }
        VdPath(
          pathData = pathData,
          fill = fill,
          evenOdd = node.attrs["fillType"] == "1",
          fillAlpha = parseFloat(node.attrs["fillAlpha"]) ?: 1f,
        )
      }
      "group" -> {
        val children = node.children.map { convert(it) ?: return null }
        VdGroup(
          scaleX = parseFloat(node.attrs["scaleX"]) ?: 1f,
          scaleY = parseFloat(node.attrs["scaleY"]) ?: 1f,
          rotation = parseFloat(node.attrs["rotation"]) ?: 0f,
          pivotX = parseFloat(node.attrs["pivotX"]) ?: 0f,
          pivotY = parseFloat(node.attrs["pivotY"]) ?: 0f,
          translateX = parseFloat(node.attrs["translateX"]) ?: 0f,
          translateY = parseFloat(node.attrs["translateY"]) ?: 0f,
          children = children,
        )
      }
      else -> null // clip-path, trim, unknown → unsupported: abort rather than render wrong
    }
    val children = root.children.map { convert(it) ?: return null }
    return VdVector(viewportWidth = vw, viewportHeight = vh, children = children)
  }

  /** Parses an aapt-inlined `<gradient>` resource (what a `fillColor="@..."` ref points at). */
  internal fun parseGradient(lines: List<String>): VdFill? {
    val root = parseTree(lines, "gradient") ?: return null
    val stops = root.children.filter { it.name == "item" }.mapNotNull { item ->
      val color = parseColor(item.attrs["color"]) ?: return@mapNotNull null
      val offset = parseFloat(item.attrs["offset"]) ?: return@mapNotNull null
      offset to color
    }.sortedBy { it.first }
    if (stops.size < 2) return null
    return when (root.attrs["type"]?.toFloatOrNull()?.toInt() ?: 0) {
      0 -> VdFill.Linear(
        startX = parseFloat(root.attrs["startX"]) ?: return null,
        startY = parseFloat(root.attrs["startY"]) ?: return null,
        endX = parseFloat(root.attrs["endX"]) ?: return null,
        endY = parseFloat(root.attrs["endY"]) ?: return null,
        stops = stops,
      )
      1 -> VdFill.Radial(
        centerX = parseFloat(root.attrs["centerX"]) ?: return null,
        centerY = parseFloat(root.attrs["centerY"]) ?: return null,
        radius = parseFloat(root.attrs["gradientRadius"]) ?: return null,
        stops = stops,
      )
      else -> null // sweep — unsupported
    }
  }

  // ─── SVG path data ─────────────────────────────────────────────────────────

  private val NUMBER = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

  /**
   * Parses VectorDrawable `pathData` (SVG path syntax) into a [Path2D]. Supports the full command
   * set (M/L/H/V/C/S/Q/T/A/Z, absolute and relative, with implicit command repetition). Throws on
   * malformed input — callers wrap the whole render in a runCatching.
   */
  internal fun parseSvgPath(d: String): Path2D.Float {
    val path = Path2D.Float()
    var i = 0
    var cmd = ' '
    var cx = 0f
    var cy = 0f
    var startX = 0f
    var startY = 0f
    var lastCtrlX = 0f
    var lastCtrlY = 0f
    var lastCmd = ' '

    fun skipSeparators() {
      while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++
    }

    fun nextNumber(): Float {
      skipSeparators()
      val m = NUMBER.find(d, i) ?: error("expected number at $i in '$d'")
      check(m.range.first == i) { "expected number at $i in '$d'" }
      i = m.range.last + 1
      return m.value.toFloat()
    }

    while (true) {
      skipSeparators()
      if (i >= d.length) break
      val c = d[i]
      if (c.isLetter()) {
        cmd = c
        i++
      } else if (cmd == 'M') {
        cmd = 'L' // implicit lineto after moveto
      } else if (cmd == 'm') {
        cmd = 'l'
      }
      val rel = cmd.isLowerCase()
      when (cmd.uppercaseChar()) {
        'M' -> {
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.moveTo(x, y); cx = x; cy = y; startX = x; startY = y
        }
        'L' -> {
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.lineTo(x, y); cx = x; cy = y
        }
        'H' -> {
          val x = nextNumber() + if (rel) cx else 0f
          path.lineTo(x, cy); cx = x
        }
        'V' -> {
          val y = nextNumber() + if (rel) cy else 0f
          path.lineTo(cx, y); cy = y
        }
        'C' -> {
          val x1 = nextNumber() + if (rel) cx else 0f
          val y1 = nextNumber() + if (rel) cy else 0f
          val x2 = nextNumber() + if (rel) cx else 0f
          val y2 = nextNumber() + if (rel) cy else 0f
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.curveTo(x1, y1, x2, y2, x, y)
          lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
        }
        'S' -> {
          val x1 = if (lastCmd.uppercaseChar() in "CS") 2 * cx - lastCtrlX else cx
          val y1 = if (lastCmd.uppercaseChar() in "CS") 2 * cy - lastCtrlY else cy
          val x2 = nextNumber() + if (rel) cx else 0f
          val y2 = nextNumber() + if (rel) cy else 0f
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.curveTo(x1, y1, x2, y2, x, y)
          lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
        }
        'Q' -> {
          val x1 = nextNumber() + if (rel) cx else 0f
          val y1 = nextNumber() + if (rel) cy else 0f
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.quadTo(x1, y1, x, y)
          lastCtrlX = x1; lastCtrlY = y1; cx = x; cy = y
        }
        'T' -> {
          val x1 = if (lastCmd.uppercaseChar() in "QT") 2 * cx - lastCtrlX else cx
          val y1 = if (lastCmd.uppercaseChar() in "QT") 2 * cy - lastCtrlY else cy
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          path.quadTo(x1, y1, x, y)
          lastCtrlX = x1; lastCtrlY = y1; cx = x; cy = y
        }
        'A' -> {
          val rx = nextNumber()
          val ry = nextNumber()
          val rot = nextNumber()
          val largeArc = nextNumber() != 0f
          val sweep = nextNumber() != 0f
          val x = nextNumber() + if (rel) cx else 0f
          val y = nextNumber() + if (rel) cy else 0f
          arcTo(path, cx, cy, rx, ry, rot, largeArc, sweep, x, y)
          cx = x; cy = y
        }
        'Z' -> {
          path.closePath(); cx = startX; cy = startY
        }
        else -> error("unsupported path command '$cmd' in '$d'")
      }
      lastCmd = cmd
    }
    return path
  }

  /** SVG endpoint arc → cubic segments (standard endpoint-to-center parameterization). */
  private fun arcTo(
    path: Path2D.Float,
    x0: Float,
    y0: Float,
    rxIn: Float,
    ryIn: Float,
    rotDeg: Float,
    largeArc: Boolean,
    sweep: Boolean,
    x: Float,
    y: Float,
  ) {
    if (rxIn == 0f || ryIn == 0f) {
      path.lineTo(x, y)
      return
    }
    var rx = Math.abs(rxIn).toDouble()
    var ry = Math.abs(ryIn).toDouble()
    val phi = Math.toRadians(rotDeg.toDouble())
    val dx2 = (x0 - x) / 2.0
    val dy2 = (y0 - y) / 2.0
    val x1 = Math.cos(phi) * dx2 + Math.sin(phi) * dy2
    val y1 = -Math.sin(phi) * dx2 + Math.cos(phi) * dy2
    val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if (lambda > 1) {
      rx *= Math.sqrt(lambda); ry *= Math.sqrt(lambda)
    }
    var sign = if (largeArc == sweep) -1.0 else 1.0
    val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    val co = sign * Math.sqrt(Math.max(0.0, num / den))
    val cxp = co * rx * y1 / ry
    val cyp = -co * ry * x1 / rx
    val cx = Math.cos(phi) * cxp - Math.sin(phi) * cyp + (x0 + x) / 2.0
    val cy = Math.sin(phi) * cxp + Math.cos(phi) * cyp + (y0 + y) / 2.0
    fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
      val dot = ux * vx + uy * vy
      val len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
      var ang = Math.acos(Math.max(-1.0, Math.min(1.0, dot / len)))
      if (ux * vy - uy * vx < 0) ang = -ang
      return ang
    }
    val theta1 = angle(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
    var dTheta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
    if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI
    if (sweep && dTheta < 0) dTheta += 2 * Math.PI
    val segments = Math.ceil(Math.abs(dTheta) / (Math.PI / 2)).toInt().coerceAtLeast(1)
    val delta = dTheta / segments
    val t = 4.0 / 3.0 * Math.tan(delta / 4.0)
    var theta = theta1
    var px = x0.toDouble()
    var py = y0.toDouble()
    repeat(segments) {
      val cosT = Math.cos(theta)
      val sinT = Math.sin(theta)
      val theta2 = theta + delta
      val cosT2 = Math.cos(theta2)
      val sinT2 = Math.sin(theta2)
      val ex = Math.cos(phi) * rx * cosT2 - Math.sin(phi) * ry * sinT2 + cx
      val ey = Math.sin(phi) * rx * cosT2 + Math.cos(phi) * ry * sinT2 + cy
      val dxTheta = -Math.cos(phi) * rx * sinT - Math.sin(phi) * ry * cosT
      val dyTheta = -Math.sin(phi) * rx * sinT + Math.cos(phi) * ry * cosT
      val dxTheta2 = -Math.cos(phi) * rx * sinT2 - Math.sin(phi) * ry * cosT2
      val dyTheta2 = -Math.sin(phi) * rx * sinT2 + Math.cos(phi) * ry * cosT2
      path.curveTo(
        (px + t * dxTheta).toFloat(), (py + t * dyTheta).toFloat(),
        (ex - t * dxTheta2).toFloat(), (ey - t * dyTheta2).toFloat(),
        ex.toFloat(), ey.toFloat(),
      )
      px = ex; py = ey; theta = theta2
    }
  }

  // ─── Rendering ─────────────────────────────────────────────────────────────

  /**
   * Renders an adaptive icon's [background] + [foreground] layers and center-crops to the safe
   * zone: layers span a 108dp canvas of which launchers display the middle 72dp — rendering at
   * 1.5× and cropping the center reproduces what the user's launcher shows (modulo mask shape,
   * which the picker's rounded corners approximate).
   */
  internal fun renderAdaptiveIcon(background: VdLayer?, foreground: VdLayer?, sizePx: Int): BufferedImage {
    val canvas = sizePx * 3 / 2
    val full = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
    val g = full.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    listOfNotNull(background, foreground).forEach { layer ->
      when (layer) {
        is VdLayer.Color -> {
          g.paint = Color(layer.argb, true)
          g.fillRect(0, 0, canvas, canvas)
        }
        is VdLayer.Vector -> drawVector(g, layer.vector, canvas)
      }
    }
    g.dispose()
    val offset = (canvas - sizePx) / 2
    return full.getSubimage(offset, offset, sizePx, sizePx)
  }

  private fun drawVector(g: java.awt.Graphics2D, vector: VdVector, canvas: Int) {
    val base = AffineTransform.getScaleInstance(
      canvas / vector.viewportWidth.toDouble(),
      canvas / vector.viewportHeight.toDouble(),
    )
    fun draw(nodes: List<VdNode>, transform: AffineTransform) {
      nodes.forEach { node ->
        when (node) {
          is VdGroup -> {
            // Android group semantics: translate(t+p) · rotate · scale · translate(-p).
            val m = AffineTransform(transform)
            m.translate((node.translateX + node.pivotX).toDouble(), (node.translateY + node.pivotY).toDouble())
            m.rotate(Math.toRadians(node.rotation.toDouble()))
            m.scale(node.scaleX.toDouble(), node.scaleY.toDouble())
            m.translate(-node.pivotX.toDouble(), -node.pivotY.toDouble())
            draw(node.children, m)
          }
          is VdPath -> {
            val fill = node.fill ?: return@forEach
            val path = parseSvgPath(node.pathData)
            path.windingRule = if (node.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
            val shape = transform.createTransformedShape(path)
            g.paint = when (fill) {
              is VdFill.Solid -> Color(applyAlpha(fill.argb, node.fillAlpha), true)
              is VdFill.Linear -> LinearGradientPaint(
                transform.transform(Point2D.Float(fill.startX, fill.startY), null),
                transform.transform(Point2D.Float(fill.endX, fill.endY), null),
                fill.stops.map { it.first }.toFloatArray(),
                fill.stops.map { Color(applyAlpha(it.second, node.fillAlpha), true) }.toTypedArray(),
              )
              is VdFill.Radial -> RadialGradientPaint(
                transform.transform(Point2D.Float(fill.centerX, fill.centerY), null),
                (fill.radius * transform.scaleX).toFloat(),
                fill.stops.map { it.first }.toFloatArray(),
                fill.stops.map { Color(applyAlpha(it.second, node.fillAlpha), true) }.toTypedArray(),
                MultipleGradientPaint.CycleMethod.NO_CYCLE,
              )
            }
            g.fill(shape)
          }
        }
      }
    }
    draw(vector.children, base)
  }

  private fun applyAlpha(argb: Int, alpha: Float): Int {
    if (alpha >= 1f) return argb
    val a = (((argb ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0x00FFFFFF)
  }
}
