package xyz.block.trailblaze.host.revyl

/**
 * Standalone demo that drives a Revyl cloud device through a Bug Bazaar
 * e-commerce flow using [RevylCliClient] — the same integration layer
 * that [RevylTrailblazeAgent] uses for all tool execution.
 *
 * Each step prints the equivalent `revyl device` CLI command and the
 * resolved coordinates returned by the CLI's AI grounding.
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
  println("Each step shows the Kotlin call, CLI command, AND resolved coordinates.\n")

  // ── Step 0: Provision device + install app ─────────────────────────
  // CLI: revyl device start --platform android --app-url <BUG_BAZAAR_APK> --json
  println("Step 0: Start device + install Bug Bazaar")
  val session = client.startSession(
    platform = "android",
    appUrl = BUG_BAZAAR_APK,
    deviceName = RevylDevicePreset.ANDROID_PHONE.presetId,
  )
  println("  Viewer: ${session.viewerUrl}")
  println("  Screen: ${session.screenWidth}x${session.screenHeight}")

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
  val r2 = client.tapTarget("Search tab")
  println("  -> Tapped at (${r2.x}, ${r2.y})")
  Thread.sleep(1000)

  // ── Step 3: Search for "beetle" ────────────────────────────────────
  // CLI: revyl device type --target "search input field" --text "beetle" --json
  println("\nStep 3: Type 'beetle' in search field")
  val r3 = client.typeText("beetle", target = "search input field")
  println("  -> Typed at (${r3.x}, ${r3.y})")
  Thread.sleep(1000)
  client.screenshot("flow-02-search.png")

  // ── Step 4: Open product detail ────────────────────────────────────
  // CLI: revyl device tap --target "Hercules Beetle" --json
  println("\nStep 4: Tap Hercules Beetle result")
  val r4 = client.tapTarget("Hercules Beetle")
  println("  -> Tapped at (${r4.x}, ${r4.y})")
  Thread.sleep(1000)
  client.screenshot("flow-03-product.png")

  // ── Step 5: Add to cart ────────────────────────────────────────────
  // CLI: revyl device tap --target "Add to Cart button" --json
  println("\nStep 5: Tap Add to Cart")
  val r5 = client.tapTarget("Add to Cart button")
  println("  -> Tapped at (${r5.x}, ${r5.y})")
  Thread.sleep(1000)

  // ── Step 6: Back to home ───────────────────────────────────────────
  // CLI: revyl device back --json
  println("\nStep 6: Navigate back to home")
  val r6a = client.back()
  println("  -> Back at (${r6a.x}, ${r6a.y})")
  val r6b = client.back()
  println("  -> Back at (${r6b.x}, ${r6b.y})")
  Thread.sleep(1000)
  client.screenshot("flow-04-done.png")

  // ── Done ───────────────────────────────────────────────────────────
  println("\n=== Demo complete ===")
  println("Session viewer: ${session.viewerUrl}")
  println("Screen dimensions: ${session.screenWidth}x${session.screenHeight}")
  println("Screenshots: flow-01-home.png … flow-04-done.png")
  println("\nStop device:")
  println("  CLI: revyl device stop")
  println("  Kotlin: client.stopSession()")
}
