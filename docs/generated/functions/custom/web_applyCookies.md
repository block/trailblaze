## Tool `web_applyCookies`

## Description
Injects a set of cookies into the current browser context. Pass [cookiesJson] as a JSON
string in Playwright's storage-state cookie array shape, e.g.:

  [{"name":"sid","value":"abc","domain":".example.com","path":"/","httpOnly":true,"secure":true,"sameSite":"Lax"}]

Typically used to replay a previously-saved authenticated session so a trail can skip a full
UI login. Pair with web_getStorageState (for capture) and web_navigate (to drive to the
authenticated landing page after applying).

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeApplyCookiesTool`

### Registered `PlaywrightNativeApplyCookiesTool` in `ToolRegistry`
### Optional Parameters
- `cookiesJson`: `String`
  JSON array of cookies in Playwright's storage-state cookie shape. May be the full storageState JSON object (in which case the `cookies` field is read), or the bare cookies array.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION