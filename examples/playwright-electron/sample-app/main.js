const { app, BrowserWindow } = require("electron");
const path = require("path");

app.whenReady().then(() => {
  const headless = process.env.ELECTRON_HEADLESS === "true";

  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    show: !headless,
    webPreferences: { contextIsolation: true },
  });

  // Reuse the playwright-native sample app HTML fixture
  const htmlPath = path.join(
    __dirname,
    "..",
    "..",
    "playwright-native",
    "sample-app",
    "index.html"
  );
  win.loadFile(htmlPath);
});

app.on("window-all-closed", () => app.quit());
