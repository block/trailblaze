The files in this package are copies of the Maestro Code, Adapted for our on-device usage.

These files should ALWAYS be updated when we upgrade our version of Maestro so that we are matching their host implementation.
Also, please perform the modifications listed below in order to stay consistent with required changes.

2025-10-23- Latest code is from v2.0.6
https://github.com/mobile-dev-inc/Maestro/blob/v2.0.6/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt

Modifications:
- Removed Support for AI Commands
- Removed API Key (This is Maestro's API Key)
- Replaced GraalJS Engine with Fake JS Engine
- Removed call to the JS Engine Usage for "evaluateCommand"
- Removed some calls to the JSEngine
- Removed the "CommandOutput" sealed class that is unused
- Copied over the internal calculateElementRelativePoint() method into Orchestra.kt
- Remove "FlowController" and "DefaultFlowController" classes and use the OSS version.