## Tool `web_waitForUrl`

## Description
Wait until the current page's URL matches the given regex pattern. Returns the matched URL.
Use after web_navigate or web_click when the navigation is async and the next step depends
on the final URL (e.g. waiting for a post-login redirect to settle).

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeWaitForUrlTool`

### Registered `PlaywrightNativeWaitForUrlTool` in `ToolRegistry`
### Required Parameters
- `pattern`: `String`
  Java regex pattern the URL must match (e.g. ".*(dashboard|home|orders).*").

### Optional Parameters
- `timeoutMs`: `Integer`
  Maximum time to wait in milliseconds. Defaults to 30000ms.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION