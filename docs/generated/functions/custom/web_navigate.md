## Tool `web_navigate`

## Description
Navigate the browser to a URL, or go back/forward in browser history.
Use action GOTO (default) with a url to navigate to a new page.
Use action BACK or FORWARD to move through browser history.
Relative file paths (e.g., 'sample-app/index.html') are resolved from the working directory.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool`

### Registered `PlaywrightNativeNavigateTool` in `ToolRegistry`
### Optional Parameters
- `action`: `[
  "GOTO",
  "BACK",
  "FORWARD"
]`
- `url`: `String`
  The URL to navigate to. Required when action is GOTO. Supports full URLs (https://..., file://...) or relative file paths.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION