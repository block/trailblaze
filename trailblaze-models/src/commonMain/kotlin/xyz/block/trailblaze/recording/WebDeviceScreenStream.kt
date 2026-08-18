package xyz.block.trailblaze.recording

/**
 * Web-specific extensions to [DeviceScreenStream] for chrome the author can't reach via the
 * rendered viewport — URL navigation and back/forward history. Maestro-backed devices don't
 * expose this surface (Android/iOS apps don't have a generic URL bar), so we keep the
 * concerns split: [DeviceScreenStream] models what every device shares, [WebDeviceScreenStream]
 * adds the bits the recording UI only renders when the stream happens to be a browser page.
 *
 * The recording UI does `stream as? WebDeviceScreenStream` to decide whether to draw the
 * URL/back/forward chrome above the device frame. Implementations are paired one-to-one with
 * a Playwright [com.microsoft.playwright.Page] (or its equivalent for any future web driver).
 */
interface WebDeviceScreenStream : DeviceScreenStream {
  /** Navigate to [url]. Equivalent to typing into the address bar and pressing Enter. */
  suspend fun navigate(url: String)

  /** Move one entry back in browser history. No-op if there's no back history. */
  suspend fun back()

  /** Move one entry forward in browser history. No-op if there's no forward history. */
  suspend fun forward()

  /**
   * Returns the page's current URL — what the address bar should display. Polled by the
   * recording chrome so in-page link clicks (which don't go through [navigate]) still
   * keep the URL field in sync.
   */
  suspend fun currentUrl(): String
}
