This is a proposed Yaml format for Trailblaze Recordings "trails" that is still under development.

There are 3 root types:
`prompt`, `tools`, and `maestro`

- `prompt`
  - `text` (required): The text of the prompt.
  - `recordable` true/false
  - `recording` (optional) - The list of `tools` that are recorded in response to the prompt.
- `tools` - A list of static tools run
- `maestro` - A list of static maestro commands to run

Current (as of June 20th, 2025) example:
```yaml
- tools:
  - launchApp:
      appId: com.example
      launchMode: FORCE_RESTART
  - customToolForTestSetup:
      str: Testing Testing 123
      strList:
      - Testing 1
      - Testing 2
- prompt:
    text: This is a prompt
    recording:
      tools:
      - inputText:
          text: Hello World
      - pressBack: {}
- prompt:
    text: This is a non-recordable prompt
    recordable: false
- prompt:
    text: This is a prompt but doesn't have a recording yet, but could.
- maestro:
  - swipe:
      duration: 400
      direction: UP
  - back
```