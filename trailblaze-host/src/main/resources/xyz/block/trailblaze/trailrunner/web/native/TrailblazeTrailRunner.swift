import Cocoa
import WebKit

let url: URL = {
  if let argv = CommandLine.arguments.dropFirst().first, !argv.isEmpty,
     let parsed = URL(string: argv) {
    return parsed
  }
  return URL(string: "http://localhost:52525/trailrunner/")!
}()

let app = NSApplication.shared
app.setActivationPolicy(.regular)

// Dock / app icon. A bare `swiftc` binary (no .app bundle) otherwise shows the
// generic executable icon in the Dock; the daemon serves the Trail Runner mark
// next to this source, so fetch it and set it as the application icon.
if let iconURL = URL(string: "native/trailrunner-icon.png", relativeTo: url),
   let iconData = try? Data(contentsOf: iconURL),
   let icon = NSImage(data: iconData) {
  app.applicationIconImage = icon
}

// Main menu. A bare `swiftc` binary gets no menu bar by default, which means no
// ⌘Q to quit and no Edit menu — so ⌘C/⌘V/⌘X/⌘A never reach the web view's text
// fields. Build the standard macOS menus by hand.
let mainMenu = NSMenu()

let appMenuItem = NSMenuItem()
mainMenu.addItem(appMenuItem)
let appMenu = NSMenu()
appMenu.addItem(
  withTitle: "About Trail Runner",
  action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
appMenu.addItem(NSMenuItem.separator())
appMenu.addItem(
  withTitle: "Hide Trail Runner", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
let hideOthersItem = NSMenuItem(
  title: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)),
  keyEquivalent: "h")
hideOthersItem.keyEquivalentModifierMask = [.command, .option]
appMenu.addItem(hideOthersItem)
appMenu.addItem(
  withTitle: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)),
  keyEquivalent: "")
appMenu.addItem(NSMenuItem.separator())
appMenu.addItem(
  withTitle: "Quit Trail Runner", action: #selector(NSApplication.terminate(_:)),
  keyEquivalent: "q")
appMenuItem.submenu = appMenu

let editMenuItem = NSMenuItem()
mainMenu.addItem(editMenuItem)
let editMenu = NSMenu(title: "Edit")
editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
editMenu.addItem(NSMenuItem.separator())
editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
editMenu.addItem(
  withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
editMenuItem.submenu = editMenu

let viewMenuItem = NSMenuItem()
mainMenu.addItem(viewMenuItem)
let viewMenu = NSMenu(title: "View")
let reloadItem = NSMenuItem(
  title: "Reload Page", action: #selector(WKWebView.reload(_:)), keyEquivalent: "r")
viewMenu.addItem(reloadItem)
viewMenuItem.submenu = viewMenu

let windowMenuItem = NSMenuItem()
mainMenu.addItem(windowMenuItem)
let windowMenu = NSMenu(title: "Window")
windowMenu.addItem(
  withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
windowMenu.addItem(
  withTitle: "Zoom", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
windowMenu.addItem(NSMenuItem.separator())
windowMenu.addItem(
  withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
windowMenuItem.submenu = windowMenu
app.windowsMenu = windowMenu

app.mainMenu = mainMenu

let window = NSWindow(
  contentRect: NSRect(x: 200, y: 200, width: 1400, height: 900),
  styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
  backing: .buffered,
  defer: false
)
window.title = "Trail Runner"
window.titleVisibility = .hidden
window.titlebarAppearsTransparent = true
window.appearance = NSAppearance(named: .darkAqua)
window.setFrameAutosaveName("TrailblazeTrailRunnerWindow")
window.minSize = NSSize(width: 1000, height: 640)

final class DirectoryPickerHandler: NSObject, WKScriptMessageHandler {
  nonisolated(unsafe) var pickerWindow: NSWindow?
  nonisolated(unsafe) var pickerWebView: WKWebView?

  func userContentController(
    _ userContentController: WKUserContentController,
    didReceive message: WKScriptMessage
  ) {
    let initialDir = (message.body as? [String: Any])?["initialDir"] as? String
    DispatchQueue.main.async { [self] in
      guard let win = self.pickerWindow, let wv = self.pickerWebView else { return }
      let panel = NSOpenPanel()
      panel.canChooseDirectories = true
      panel.canChooseFiles = false
      panel.allowsMultipleSelection = false
      panel.prompt = "Choose"
      panel.message = "Choose a directory of .trail.yaml files"
      if let d = initialDir, !d.isEmpty { panel.directoryURL = URL(fileURLWithPath: d) }
      panel.beginSheetModal(for: win) { response in
        let js: String
        if response == .OK, let path = panel.url?.path {
          // JSON-encode the path into a JS string literal so newlines / control chars /
          // quotes / backslashes in the directory name can't break the injected JS.
          let arg = (try? JSONSerialization.data(withJSONObject: path, options: .fragmentsAllowed))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "null"
          js = "window.__trailblazeOnDirectoryPicked(\(arg))"
        } else {
          js = "window.__trailblazeOnDirectoryPicked(null)"
        }
        wv.evaluateJavaScript(js, completionHandler: nil)
      }
    }
  }
}
let directoryPickerHandler = DirectoryPickerHandler()

// Reveal a file/folder in Finder from the GUI shell. The daemon runs headless + detached, so its
// `open -R` can't reliably reach Finder (same reason directory picking is native above) — reveal
// belongs here, where NSWorkspace has the Aqua session. Body: { path: "<absolute path>" }.
final class RevealHandler: NSObject, WKScriptMessageHandler {
  func userContentController(
    _ userContentController: WKUserContentController,
    didReceive message: WKScriptMessage
  ) {
    guard let path = (message.body as? [String: Any])?["path"] as? String, !path.isEmpty else { return }
    DispatchQueue.main.async {
      NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }
  }
}
let revealHandler = RevealHandler()

func offlineHTML(_ target: URL) -> String {
  let shown = target.absoluteString
  return """
  <!doctype html><html><head><meta charset="utf-8"><style>
  html,body{height:100%;margin:0;background:#0b0d0f;color:#e6e8ea;font-family:-apple-system,BlinkMacSystemFont,'SF Pro Text',sans-serif;display:flex;align-items:center;justify-content:center}
  .card{text-align:center;max-width:440px;padding:32px}
  .dot{width:10px;height:10px;border-radius:50%;background:#f5b041;display:inline-block;margin-right:9px;vertical-align:1px;animation:pulse 1.2s ease-in-out infinite}
  @keyframes pulse{0%,100%{opacity:.25}50%{opacity:1}}
  h1{font-size:19px;font-weight:600;margin:0 0 10px}
  p{font-size:13.5px;line-height:1.55;color:#9aa0a6;margin:0 0 22px}
  code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#c8cdd2}
  button{font:inherit;font-size:13px;font-weight:600;color:#0b0d0f;background:#00E013;border:none;border-radius:8px;padding:9px 18px;cursor:pointer}
  .status{font-size:12px;color:#6b7177;margin-top:16px}
  </style></head><body>
  <div class="card">
    <h1><span class="dot"></span>Waiting for the Trailblaze daemon</h1>
    <p>Couldn’t reach the local daemon at <code>\(shown)</code>. It may be starting up or restarting after a rebuild. This window reconnects automatically.</p>
    <button onclick="if(window.webkit&&webkit.messageHandlers&&webkit.messageHandlers.reconnect){webkit.messageHandlers.reconnect.postMessage({})}">Retry now</button>
    <div class="status">Reconnecting…</div>
  </div>
  </body></html>
  """
}

final class ShellController: NSObject, WKNavigationDelegate, WKScriptMessageHandler, WKUIDelegate {
  nonisolated(unsafe) var webView: WKWebView?
  nonisolated(unsafe) var reconnectTimer: Timer?
  nonisolated(unsafe) var showingOffline = false
  let target: URL
  init(target: URL) { self.target = target }

  func webView(_ wv: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
    showOffline()
  }
  func webView(_ wv: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
    showOffline()
  }
  func webView(_ wv: WKWebView, didFinish navigation: WKNavigation!) {
    if showingOffline {
    }
  }

  func showOffline() {
    DispatchQueue.main.async { [self] in
      showingOffline = true
      webView?.loadHTMLString(offlineHTML(target), baseURL: nil)
      startPolling()
    }
  }

  func startPolling() {
    reconnectTimer?.invalidate()
    reconnectTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [self] _ in probe() }
  }

  func probe() {
    var req = URLRequest(url: target)
    req.timeoutInterval = 2
    URLSession.shared.dataTask(with: req) { [self] _, resp, err in
      guard showingOffline, err == nil, let http = resp as? HTTPURLResponse, http.statusCode < 500 else { return }
      DispatchQueue.main.async { [self] in
        guard showingOffline else { return }
        showingOffline = false
        reconnectTimer?.invalidate(); reconnectTimer = nil
        webView?.load(URLRequest(url: target))
      }
    }.resume()
  }

  func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
    probe()
  }

  func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
               initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
    let alert = NSAlert()
    alert.messageText = message
    alert.addButton(withTitle: "OK")
    if let win = webView.window { alert.beginSheetModal(for: win) { _ in completionHandler() } }
    else { alert.runModal(); completionHandler() }
  }
  func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
               initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
    let alert = NSAlert()
    alert.messageText = message
    alert.addButton(withTitle: "OK")
    alert.addButton(withTitle: "Cancel")
    if let win = webView.window {
      alert.beginSheetModal(for: win) { resp in completionHandler(resp == .alertFirstButtonReturn) }
    } else {
      completionHandler(alert.runModal() == .alertFirstButtonReturn)
    }
  }
}
let shellController = ShellController(target: url)

let webConfig = WKWebViewConfiguration()
// Persistent store so the browser keeps unpkg's immutable, versioned CDN libs
// (React, Babel, CodeMirror, …) on disk across launches instead of re-fetching
// ~4MB every open. Our own app assets are served `Cache-Control: no-store` by
// the daemon, so a rebuild is never served stale despite the persistent cache.
webConfig.websiteDataStore = WKWebsiteDataStore.default()

webConfig.userContentController.add(directoryPickerHandler, name: "pickDirectory")
webConfig.userContentController.add(shellController, name: "reconnect")
webConfig.userContentController.add(revealHandler, name: "revealPath")

let pickerShimSource = """
// Mark the document as running inside the native shell so the web layer reserves
// a top inset for the chromeless (fullSizeContentView) title-bar + traffic lights.
document.documentElement.classList.add('tb-native');
// Suppress WebKit's right-click context menu (Reload/Back/Forward) — this is a
// native app, not a web page — EXCEPT over editors, inputs, and selectable text,
// where the menu carries Copy/Paste and is the only mouse path to the clipboard.
window.addEventListener('contextmenu', function(e) {
  var t = e.target;
  var editable = t && t.closest && t.closest('input, textarea, .CodeMirror, [data-selectable], pre, code, .tb-mono');
  var hasSelection = String(window.getSelection ? window.getSelection() : '').length > 0;
  if (!editable && !hasSelection) e.preventDefault();
}, true);
window.trailblazePickDirectory = function(initialDir) {
  return new Promise(function(resolve) {
    window.__trailblazeOnDirectoryPicked = function(path) {
      resolve(path && path.length ? path : null);
      window.__trailblazeOnDirectoryPicked = null;
    };
    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.pickDirectory) {
      window.webkit.messageHandlers.pickDirectory.postMessage({ initialDir: initialDir || null });
    } else {
      var p = window.prompt('Absolute path to directory of .trail.yaml files:', initialDir || '');
      window.__trailblazeOnDirectoryPicked(p || '');
    }
  });
};
// Reveal an absolute path in Finder via the native handler. Returns true when it dispatched to the
// shell (so the web layer knows not to fall back to the daemon's headless reveal); false in a plain
// browser where this bridge isn't present.
window.trailblazeRevealPath = function(path) {
  if (path && window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.revealPath) {
    window.webkit.messageHandlers.revealPath.postMessage({ path: path });
    return true;
  }
  return false;
};
"""
let pickerShim = WKUserScript(
  source: pickerShimSource,
  injectionTime: .atDocumentStart,
  forMainFrameOnly: false
)
webConfig.userContentController.addUserScript(pickerShim)

let webView = WKWebView(frame: window.contentView!.bounds, configuration: webConfig)

directoryPickerHandler.pickerWindow = window
directoryPickerHandler.pickerWebView = webView
webView.autoresizingMask = [.width, .height]
webView.allowsBackForwardNavigationGestures = true
webView.setValue(false, forKey: "drawsBackground")
shellController.webView = webView
webView.navigationDelegate = shellController
webView.uiDelegate = shellController
webView.load(URLRequest(url: url))

window.contentView!.addSubview(webView)

let dragStrip = TitlebarDragView(frame: NSRect(
  x: 0, y: window.contentView!.bounds.height - 28,
  width: window.contentView!.bounds.width, height: 28
))
dragStrip.autoresizingMask = [.width, .minYMargin]
window.contentView!.addSubview(dragStrip)

window.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)

final class TitlebarDragView: NSView {
  override func mouseDown(with event: NSEvent) {
    window?.performDrag(with: event)
  }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
  func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
let delegate = AppDelegate()
app.delegate = delegate

app.run()
