## Tool `web_resize`

## Description
Resize the browser viewport to the given dimensions.

Use to test responsive CSS at different breakpoints (e.g., 375x812 for phone,
768x1024 for tablet). Does NOT change User-Agent or device emulation flags —
pages that UA-sniff still see desktop Chrome. For full mobile emulation set
the device's profile at creation time, not via this tool.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeResizeTool`

### Registered `PlaywrightNativeResizeTool` in `ToolRegistry`
### Required Parameters
- `width`: `Integer`
  Width of the viewport in CSS pixels. Must be positive.
- `height`: `Integer`
  Height of the viewport in CSS pixels. Must be positive.

### Optional Parameters
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION