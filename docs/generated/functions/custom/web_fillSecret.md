## Tool `web_fillSecret`

## Description
INTERNAL — fills a form field with a value that must NOT be logged (passwords, tokens, OTPs).
Prefer plain web_type for any value that isn't sensitive, since the LLM-facing recording is
more useful when the value is visible. Use only from scripted tools where the value is loaded
from a trusted source (secrets store, fixture file, etc.).

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeFillSecretTool`

### Registered `PlaywrightNativeFillSecretTool` in `ToolRegistry`
### Required Parameters
- `ref`: `String`
  Element ID, ARIA descriptor (e.g., 'textbox "Password"'), or CSS selector with css= prefix.
- `value`: `String`
  The secret value to fill. Never logged anywhere on the host side.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION