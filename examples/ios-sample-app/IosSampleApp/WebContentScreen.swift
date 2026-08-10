import SwiftUI
import WebKit

/// Controlled fixture for the `IOS_AXE` driver's web-content descent.
///
/// iOS renders `WKWebView` page content in a separate WebContent process, so none of the elements
/// below appear in an in-process accessibility walk. They are only reachable by hit-testing across
/// the process boundary, which is what `axe describe-ui --include-web-content` does. Driven by
/// `trails/eval/ios/sample-app/webview-content.trail.yaml`.
struct WebContentScreen: View {
  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      // Native, so a failing trail can distinguish "never reached the screen" from
      // "reached it but could not see into the web view".
      Text("Native label above the web view")
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityIdentifier("label_web_native")

      WebContentFixture()
        .accessibilityIdentifier("webview_fixture")
    }
    .padding()
    .navigationTitle("Web Content")
  }
}

private struct WebContentFixture: UIViewRepresentable {
  /// Loaded from a string, not a URL: the fixture has to render identically on a CI simulator with
  /// no network access.
  private static let html = """
    <!DOCTYPE html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font: 17px -apple-system, sans-serif; margin: 12px; }
          /* Generous vertical spacing so every element lands in its own hit-test probe row. */
          h1 { font-size: 24px; margin: 0 0 28px; }
          p, div { margin: 0 0 28px; }
          button, input { font-size: 17px; padding: 8px; }
        </style>
      </head>
      <body>
        <h1>Web heading in a separate process</h1>
        <p>Web paragraph rendered by WebKit</p>
        <div><a href="#">Web link target</a></div>
        <div><button type="button">Web button target</button></div>
        <div><input type="text" aria-label="Web field target" value="web field value"></div>
      </body>
    </html>
    """

  func makeUIView(context: Context) -> WKWebView {
    let webView = WKWebView(frame: .zero)
    webView.isOpaque = false
    webView.scrollView.isScrollEnabled = false
    webView.loadHTMLString(Self.html, baseURL: nil)
    return webView
  }

  func updateUIView(_ webView: WKWebView, context: Context) {}
}

#Preview {
  NavigationStack {
    WebContentScreen()
  }
}
