package xyz.block.trailblaze.usages

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The behavior under test is "which tools does a blast radius have to include", so every case is
 * written against that question rather than against the regexes. The bundle text in each fixture is
 * shaped like real esbuild output — a flattened IIFE with the receiver already renamed — because the
 * whole reason this scans the BUNDLE instead of the source is that the bundle survives aliasing and
 * helper indirection that a source-level scan does not.
 */
class ToolCallerEdgeScannerTest {

  private fun snapshot(vararg names: String) = ToolSourceSnapshot(
    toolSources = names.associate {
      ToolKey(trailmap = "myapp", name = it) to ToolSource(script = File("/tmp/$it.ts"))
    },
  )

  @Test
  fun `a property call on a renamed receiver is still an edge`() {
    // esbuild does not rewrite property names, so the dispatch survives however the receiver was
    // aliased on the way in. A source-level `ctx.tools.` scan sees nothing here.
    val bundle = """
      var __ctx = arguments[0];
      function helper(t) { return t.myapp_addItemToCart({ itemName: "Latte" }); }
    """.trimIndent()

    val referenced = ToolCallerEdgeScanner.referencedToolsIn(
      bundle = bundle,
      candidates = setOf("myapp_addItemToCart", "myapp_signIn"),
      self = "myapp_checkout",
    )

    assertEquals(setOf("myapp_addItemToCart"), referenced)
  }

  @Test
  fun `a string-keyed dispatch is an edge, including for a name that is not a JS identifier`() {
    val bundle = """await client.callTool("myapp-android-launchApp", args); tools["myapp_signIn"](x);"""

    val referenced = ToolCallerEdgeScanner.referencedToolsIn(
      bundle = bundle,
      candidates = setOf("myapp-android-launchApp", "myapp_signIn"),
      self = "myapp_checkout",
    )

    assertEquals(setOf("myapp-android-launchApp", "myapp_signIn"), referenced)
  }

  @Test
  fun `a tool name merely mentioned in prose is not an edge`() {
    // Over-reporting is the accepted bias, but a name inside a message is not dispatch-shaped, and
    // treating it as one would make every tool that documents another tool its caller.
    val bundle = """throw new Error("expected myapp_signIn to have run before this point");"""

    val referenced = ToolCallerEdgeScanner.referencedToolsIn(
      bundle = bundle,
      candidates = setOf("myapp_signIn"),
      self = "myapp_checkout",
    )

    assertEquals(emptySet<String>(), referenced)
  }

  @Test
  fun `a tool does not call itself`() {
    // Every bundle registers its own name (the synthesized wrapper does it), so without excluding
    // self every tool would be its own caller and the fixpoint below would flag the whole estate.
    val bundle = """globalThis.__trailblazeTools["myapp_signIn"] = { handler: async (a, c) => {} };"""

    val referenced = ToolCallerEdgeScanner.referencedToolsIn(
      bundle = bundle,
      candidates = setOf("myapp_signIn"),
      self = "myapp_signIn",
    )

    assertEquals(emptySet<String>(), referenced)
  }

  @Test
  fun `a property call whose name is not a registered tool is not an edge`() {
    val bundle = """const s = raw.trim(); arr.map(x => x.toString());"""

    val referenced = ToolCallerEdgeScanner.referencedToolsIn(
      bundle = bundle,
      candidates = setOf("myapp_signIn"),
      self = "myapp_checkout",
    )

    assertEquals(emptySet<String>(), referenced)
  }

  @Test
  fun `impact reaches a caller through a chain of delegations`() {
    // a -> b -> c, and c changed. Both a and b run different code now; a depth-1 answer would
    // replay b's trails and leave a's silently uncovered.
    val edges = mapOf(
      "a" to setOf("b"),
      "b" to setOf("c"),
      "c" to emptySet(),
      "unrelated" to setOf("d"),
    )

    assertEquals(listOf("a", "b"), ToolCallerEdgeScanner.impactedBy(setOf("c"), edges))
  }

  @Test
  fun `a dispatch cycle terminates instead of looping`() {
    val edges = mapOf("a" to setOf("b"), "b" to setOf("a", "c"))

    assertEquals(listOf("a", "b"), ToolCallerEdgeScanner.impactedBy(setOf("c"), edges))
  }

  @Test
  fun `a changed tool is not reported as impacted by itself`() {
    // It is already in `modified`; repeating it under `impactedViaCallers` would double-count it
    // and blur the line between the certain tier and the inferred one.
    val edges = mapOf("a" to setOf("b"), "b" to setOf("a"))

    assertEquals(listOf("b"), ToolCallerEdgeScanner.impactedBy(setOf("a"), edges))
  }

  @Test
  fun `a caller of a REMOVED tool is impacted`() {
    // The strongest case for replaying: the callee is gone, so the caller is now broken.
    val current = snapshot("caller")
    val edges = ToolCallerEdgeScanner.scan(current, changed = setOf("deleted_tool")) { _, _ ->
      Result.success("""await client.callTool("deleted_tool", {});""")
    }

    assertEquals(setOf("deleted_tool"), edges.edges["caller"])
    assertEquals(listOf("caller"), ToolCallerEdgeScanner.impactedBy(setOf("deleted_tool"), edges.edges))
  }

  @Test
  fun `a tool whose bundle cannot be produced is reported, not silently dropped`() {
    // `runtime: subprocess` tools import Node built-ins the in-process bundler cannot resolve. A
    // caller nobody scanned looks exactly like a caller with no edges, so this has to be said out
    // loud or the report overstates its own coverage.
    val current = snapshot("scannable", "subprocess_tool")
    val result = ToolCallerEdgeScanner.scan(current, changed = setOf("myapp_signIn")) { _, name ->
      if (name == "subprocess_tool") {
        Result.failure(IOException("""Could not resolve "node:fs""""))
      } else {
        Result.success("""x.myapp_signIn();""")
      }
    }

    assertEquals(setOf("subprocess_tool"), result.unscannable.keys)
    val diagnostic = result.unscannableDiagnostics().single()
    val warning = diagnostic.message
    assertEquals(UsagesDiagnostic.CALLER_SCAN_UNAVAILABLE, diagnostic.kind)
    assertEquals(
      "impactedViaCallers",
      diagnostic.subject,
      "the consequence belongs to the FIELD whose completeness is at stake, not to any one tool — " +
        "which is why this is one diagnostic naming every unscanned tool rather than one per tool",
    )
    assertTrue(warning.contains("subprocess_tool"), "names the unscanned tool: $warning")
    assertTrue(warning.contains("runtime: subprocess"), "explains the subprocess cause: $warning")
    // Still usable as a CALLEE, and the tools that COULD be scanned still produce their edges.
    assertEquals(setOf("myapp_signIn"), result.edges["scannable"])
  }

  @Test
  fun `an unexpected bundling failure names its own cause instead of being blamed on subprocess`() {
    // The whole class of real failures — no esbuild on PATH, unreadable file, full disk — produces
    // the same empty edge set as an expected subprocess tool. Reporting the actual reason is what
    // lets a reader tell "nothing to find here" apart from "this scan was broken".
    val result = ToolCallerEdgeScanner.scan(snapshot("broken"), changed = setOf("myapp_signIn")) { _, _ ->
      Result.failure(IOException("Permission denied: /tmp/broken.ts\n  at some.stack.frame"))
    }

    assertEquals("Permission denied: /tmp/broken.ts", result.unscannable["broken"])
    assertTrue(
      result.unscannableDiagnostics().single().message.contains("Permission denied: /tmp/broken.ts"),
      "the diagnostic carries the real reason: ${result.unscannableDiagnostics()}",
    )
  }

  @Test
  fun `a failure with no message still reports something a reader can act on`() {
    val result = ToolCallerEdgeScanner.scan(snapshot("broken"), changed = setOf("myapp_signIn")) { _, _ ->
      Result.failure(IllegalStateException())
    }

    assertEquals("IllegalStateException", result.unscannable["broken"])
  }

  @Test
  fun `nothing changed means nothing is scanned`() {
    // The scan costs one bundle per tool in the workspace; with an empty changed set there is
    // nothing for an edge to lead to, so paying that is pure waste.
    var bundled = 0
    val result = ToolCallerEdgeScanner.scan(snapshot("a", "b"), changed = emptySet()) { _, _ ->
      bundled++
      Result.success("")
    }

    assertEquals(0, bundled)
    assertEquals(emptyMap<String, Set<String>>(), result.edges)
  }
}
