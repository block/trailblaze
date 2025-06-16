### Upcoming Features

- Standardized YAML Format: This will provide a static way to express your Trailblaze tests mixing natural language
  objectives and static steps
- Recording: Save any agent tools and ui interactions, then play them back to save costs during future runs.
- Test Failure Recovery with AI: When a recorded test fails, use the AI Agent to recover and complete the objectives.
  Then leverage the reporting tools to show divergence from the original path.
- MCP Server: Interactively create new tests and manage your test suite.
- Bring your own LLM Provider: OpenAI support currently, but future plans for other providers.
- LLM call proxying support (optional): Provide support for proxying traffic to the LLM, removing the requirement of
  passing an API key the mobile device as an execution argument.

### Longer Term Vision

- iOS testing support
- Web testing support
- Host mode execution: Execute the tests in an environment connected to a device or emulator to allow more flexibility
  in test execution.

### Overall Vision

- Create an open platform for authoring and executing tests using agentic AI
- Enable teams to ship faster by lowering the bar to test execution