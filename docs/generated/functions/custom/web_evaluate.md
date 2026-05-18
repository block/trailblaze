## Tool `web_evaluate`

## Description
Executes a JavaScript expression in the current page context and returns the result as a
string. Useful for reaching into in-page globals (e.g. analytics SDKs, feature flags) that
aren't exposed via the DOM. Scripted-tool only by default.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeEvaluateTool`

### Registered `PlaywrightNativeEvaluateTool` in `ToolRegistry`
### Required Parameters
- `script`: `String`
  JavaScript expression or IIFE. Use `(() => { ... })()` shape if you need statements.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION