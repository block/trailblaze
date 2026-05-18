package xyz.block.trailblaze.ui.devices

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import xyz.block.trailblaze.util.Console

/**
 * Triggers a browser download of [yaml] as a `.trail.yaml` file. Standard "create blob,
 * point an anchor at the object URL, click it programmatically" recipe for saving
 * generated content from a wasmJs / JS page without a server round-trip.
 *
 * **Revoke timing.** We do *not* call [URL.revokeObjectURL] synchronously after [anchor.click] —
 * per the spec the object URL must stay valid until the download starts, and Safari/Firefox can
 * race the synchronous revoke before they've handed the bytes to the OS, resulting in a silent
 * empty save. The blob is GC'd by the browser when the document unloads anyway; the leak window
 * is bounded to the page lifetime, which is the right tradeoff for a developer tool that's
 * generating small (~KB) YAML payloads. If this ever grows to MB-sized downloads, replace with
 * `kotlinx.browser.window.setTimeout({ URL.revokeObjectURL(url) }, 1000)` and ignore the wasmJs
 * setTimeout interop ergonomics.
 *
 * **Popup-blocker / strict-CSP.** A programmatic [anchor.click] can be silenced — by browser
 * preferences, CSP, or extensions. The previous version swallowed that case completely; now we
 * wrap the trigger in try/catch and surface failures to the browser console so the dev has
 * a clue when "Save Trail did nothing" lands in the support queue.
 */
internal fun downloadTrailYaml(yaml: String, filename: String) {
  // BlobPropertyBag.type sets the MIME so the browser/OS picks a sensible app to open
  // the file with. text/yaml has had patchy support historically — application/yaml is
  // the IANA-registered type as of 2024 and Chrome/Safari both accept it without a fuss.
  val parts = JsArray<JsAny?>()
  parts[0] = yaml.toJsString()
  val blob = Blob(
    blobParts = parts,
    options = BlobPropertyBag(type = "application/yaml"),
  )
  val url = URL.createObjectURL(blob)
  val anchor = document.createElement("a") as HTMLAnchorElement
  anchor.href = url
  anchor.download = filename
  document.body?.appendChild(anchor)
  try {
    anchor.click()
  } catch (e: Throwable) {
    Console.log("[DownloadTrailYaml] download failed (popup blocker / strict CSP?): ${e.message}")
  } finally {
    // Always remove the anchor — even if click() throws — so we don't leak orphan
    // anchors in the DOM across repeated download attempts.
    document.body?.removeChild(anchor)
  }
}
