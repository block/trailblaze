package xyz.block.trailblaze.host.revyl

/**
 * Standalone demo that drives a Revyl cloud device through a Bug Bazaar
 * e-commerce flow using [RevylCliClient] — the same integration layer
 * that [RevylTrailblazeAgent] uses for all tool execution.
 *
 * Each step prints the equivalent `revyl device` CLI command so the
 * mapping between Trailblaze Kotlin code and the Revyl CLI is visible.
 *
 * Usage:
 *   ./gradlew :trailblaze-host:run -PmainClass=xyz.block.trailblaze.host.revyl.RevylDemoKt
 */

private const val BUG_BAZAAR_APK =
  "https://pub-b03f222a53c447c18ef5f8d365a2f00e.r2.dev/bug-bazaar/bug-bazaar-preview.apk"

private const val BUG_BAZAAR_IOS =
  "https://pub-b03f222a53c447c18ef5f8d365a2f00e.r2.dev/bug-bazaar/bug-bazaar-preview-simulator.tar.gz"

private const val BUNDLE_ID = "com.bugbazaar.app"

fun main() {
  val client = RevylCliClient()

  println("\n=== Trailblaze x Revyl Demo ===")
  println("Each step shows the Kotlin call AND the equivalent CLI command.\n")

  // ── Step 0: Provision device + install app ─────────────────────────
  // CLI: revyl device start --platform android --app-url <BUG_BAZAAR_APK> --open --json
  println("Step 0: Start device + install Bug Bazaar")
  val session = client.startSession(
    platform = "android",
    appUrl = BUG_BAZAAR_APK,
  )
  println("  Viewer: ${session.viewerUrl}")

  // CLI: revyl device launch --bundle-id com.bugbazaar.app --json
  println("\nStep 0b: Launch app")
  client.launchApp(BUNDLE_ID)
  Thread.sleep(2000)

  // ── Step 1: Screenshot home ────────────────────────────────────────
  // CLI: revyl device screenshot --out flow-01-home.png --json
  println("\nStep 1: Screenshot home screen")
  client.screenshot("flow-01-home.png")

  // ── Step 2: Navigate to search ─────────────────────────────────────
  // CLI: revyl device tap --target "Search tab" --json
  println("\nStep 2: Tap Search tab")
  client.tapTarget("Search tab")
  Thread.sleep(1000)

  // ── Step 3: Search for "beetle" ────────────────────────────────────
  // CLI: revyl device type --target "search input field" --text "beetle" --json
  println("\nStep 3: Type 'beetle' in search field")
  client.typeText("beetle", target = "search input field")
  Thread.sleep(1000)
  // CLI: revyl device screenshot --out flow-02-search.png --json
  client.screenshot("flow-02-search.png")

  // ── Step 4: Open product detail ────────────────────────────────────
  // CLI: revyl device tap --target "Hercules Beetle" --json
  println("\nStep 4: Tap Hercules Beetle result")
  client.tapTarget("Hercules Beetle")
  Thread.sleep(1000)
  // CLI: revyl device screenshot --out flow-03-product.png --json
  client.screenshot("flow-03-product.png")

  // ── Step 5: Add to cart ────────────────────────────────────────────
  // CLI: revyl device tap --target "Add to Cart button" --json
  println("\nStep 5: Tap Add to Cart")
  client.tapTarget("Add to Cart button")
  Thread.sleep(1000)

  // ── Step 6: Back to home ───────────────────────────────────────────
  // CLI: revyl device back --json
  println("\nStep 6: Navigate back to home")
  client.back()
  client.back()
  Thread.sleep(1000)
  // CLI: revyl device screenshot --out flow-04-done.png --json
  client.screenshot("flow-04-done.png")

  // ── Done ───────────────────────────────────────────────────────────
  println("\n=== Demo complete ===")
  println("Session viewer: ${session.viewerUrl}")
  println("Screenshots: flow-01-home.png … flow-04-done.png")
  println("\nGet session report:")
  println("  CLI: revyl device report --json")
  println("  Kotlin: // report data available via session.viewerUrl")
  println("\nStop device:")
  println("  CLI: revyl device stop")
  println("  Kotlin: client.stopSession()")
}
