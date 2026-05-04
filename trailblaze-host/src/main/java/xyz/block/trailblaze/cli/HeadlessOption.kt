package xyz.block.trailblaze.cli

import picocli.CommandLine
import xyz.block.trailblaze.util.Console

/**
 * Picocli mixin for the `--headless` flag shared by every CLI command that may
 * bind a `--device web/...` instance. Centralizing the option keeps the help
 * text consistent across commands.
 *
 * The field is nullable so we can distinguish "user didn't pass `--headless`"
 * (null → fall back to config) from "user explicitly chose a value" (non-null
 * wins). [resolve] folds the persisted `showWebBrowser` setting in for the
 * fallback path so a one-time `trailblaze config web-headless ...` controls
 * every command that takes this mixin without per-call flags.
 *
 * Ignored for non-web platforms — it only flows through to the daemon when the
 * resolved `--device` targets the WEB platform.
 */
class HeadlessOption {
  @CommandLine.Option(
    names = ["--headless"],
    description = [
      "For --device web/...: launch the Playwright browser headless. " +
        "When omitted, falls back to the persisted `web-headless` config " +
        "(see `trailblaze config web-headless`). " +
        "Pass --headless=false to force a visible browser, --headless=true to force headless. " +
        "Ignored for non-web devices.",
    ],
    arity = "1",
  )
  var headless: Boolean? = null

  /**
   * Returns the effective headless value: the explicit `--headless` if the user
   * passed one, otherwise the inverse of the persisted `showWebBrowser` config
   * (so `showWebBrowser=true` → `headless=false`).
   *
   * If the config can't be read for any reason (corrupt file, brand-new
   * install), defaults to `false` — matches `SavedTrailblazeAppConfig.showWebBrowser`'s
   * default of `true` (visible browser) so behavior stays consistent with the
   * desktop-app default.
   */
  fun resolve(): Boolean {
    headless?.let { return it }
    val showBrowser = runCatching { CliConfigHelper.readConfig()?.showWebBrowser }
      .onFailure { Console.log("HeadlessOption.resolve: config read failed, using default — ${it.message}") }
      .getOrNull() ?: true
    return !showBrowser
  }
}
