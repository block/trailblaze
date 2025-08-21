# WASM Embedding Guide

This guide explains how to create a single HTML file with embedded WASM and JavaScript files for the Trailblaze UI.

## Overview

The `embedWasmHtml` Gradle task creates a self-contained HTML file that includes:
- The original `index.html` template from `src/wasmJsMain/resources/`
- Base64-encoded WASM files embedded directly in the HTML
- Inlined JavaScript code from `composeApp.js`
- Custom JavaScript loader that intercepts WASM fetch requests

## Usage

### 1. Build the WASM artifacts

First, build the WASM and JavaScript files:

```bash
./gradlew :trailblaze-ui:wasmJsBrowserDevelopmentWebpack
```

This generates the following files in `build/kotlin-webpack/wasmJs/developmentExecutable/`:
- `composeApp.js` - The main JavaScript bundle
- `*.wasm` - WASM binary files (typically 2 files for Kotlin/WASM + Compose)

### 2. Create the embedded HTML

Run the embedding task:

```bash
./gradlew :trailblaze-ui:embedWasmHtml
```

This will:
1. Read the HTML template from `src/wasmJsMain/resources/index.html`
2. Base64 encode all `.wasm` files found in the build directory
3. Inline the JavaScript content from `composeApp.js`
4. Create a new HTML file at `build/embedded/trailblaze-embedded.html`

### 3. Use the embedded HTML

The generated `trailblaze-embedded.html` file is completely self-contained and can be:
- Opened directly in a web browser
- Served from any web server
- Shared as a single file without external dependencies

## How it Works

### WASM Loading Override

The embedded HTML includes a custom JavaScript loader that:

1. **Stores WASM files as base64**: All WASM files are encoded as base64 strings and stored in a JavaScript object
2. **Overrides `fetch()`**: Intercepts fetch requests for `.wasm` files and returns the embedded data instead
3. **Overrides `WebAssembly.instantiateStreaming()`**: Ensures WASM instantiation works with embedded data
4. **Provides fallback**: Non-WASM fetch requests are passed through to the original fetch implementation

### File Structure

```
build/embedded/
└── trailblaze-embedded.html    # Final embedded HTML file

build/kotlin-webpack/wasmJs/developmentExecutable/
├── composeApp.js               # JavaScript bundle (gets inlined)
├── <hash1>.wasm               # WASM file 1 (gets base64 encoded)
└── <hash2>.wasm               # WASM file 2 (gets base64 encoded)
```

## Benefits

- **Single file distribution**: No need to manage multiple files or worry about relative paths
- **No external dependencies**: Everything needed to run the app is embedded
- **Works offline**: No network requests needed after initial load
- **Easy deployment**: Just upload one HTML file

## Limitations

- **File size**: The embedded HTML will be larger than the sum of original files due to base64 encoding overhead (~33% increase)
- **Memory usage**: Browser needs to load and decode all WASM data at once
- **Development**: This is intended for distribution, not development (use regular WASM serving for development)

## Troubleshooting

### Build files not found
If you get errors about missing build files, make sure to run the WASM build first:
```bash
./gradlew :trailblaze-ui:wasmJsBrowserDevelopmentWebpack
```

### Browser compatibility
The embedded HTML requires modern browser support for:
- WebAssembly
- Base64 `atob()` function
- `fetch()` API override
- ES6 features

### Large file sizes
If the embedded HTML is too large, consider:
- Using production builds instead of development builds
- Optimizing the WASM compilation settings
- Splitting the application into smaller chunks