---
title: Maestro Compatibility
---

Trailblaze uses the [Maestro](https://maestro.dev) Command Model for UI commands, however does not implement the entire spec.

> **Scope note:** "Maestro" here refers only to the UI *command model* (the selector/action syntax). It is not the execution driver: the default Android driver is Trailblaze's own on-device accessibility driver, and Maestro-shape commands are translated onto whichever driver runs the session. Maestro-backed execution survives as a legacy host-mode path.

#### Implemented:
UI Interactions like `tapOn`, `inputText`, `swipe`, etc.

#### Not Implemented:
Dynamic behavior through Maestro

- JavaScript Support
- Subflows
- Environment Variables
