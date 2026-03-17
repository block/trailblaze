---
title: Roadmap
---

### Recently Completed ✓

- **Multi-Agent V3 Features** ([Mobile-Agent-v3 inspired](https://arxiv.org/abs/2508.15144)):
  - Exception Handling: Automatic popup, ad, and error recovery
  - Reflection: Self-correction when stuck or looping
  - Task Decomposition: Break complex objectives into subtasks
  - Cross-App Memory: Remember facts across app switches
  - Enhanced Recording: Pre/post conditions for robust replay
  - Progress Reporting: Real-time MCP events for IDE integrations
- **Trail/Blaze Architecture**: "Blaze once, trail forever" workflow
- **Standardized YAML Format**: Express tests mixing natural language and static steps
- **Recording**: Save agent actions, replay deterministically to save costs

### Upcoming Features

- **Benchmark Integration**: Run AndroidWorld/OSWorld benchmarks against Trailblaze
- **LLM Call Proxying** (optional): Proxy LLM traffic to remove API key requirement on device
- **Self-Evolving Data**: Auto-generate trail files from successful benchmark runs

### Longer Term Vision

- iOS testing support
- Web testing support
- Host mode execution: Execute tests connected to a device or emulator for more flexibility

### Overall Vision

- Create an open platform for authoring and executing tests using agentic AI
- Enable teams to ship faster by lowering the bar to test execution
- Achieve competitive performance on standard benchmarks (AndroidWorld 70%+, OSWorld 35%+)