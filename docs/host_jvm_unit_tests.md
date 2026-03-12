# Host JVM Unit Tests
Host JVM unit tests run Trailblaze from JUnit on your machine. They are
separate from the desktop app and are only needed if you are running tests from
Gradle or an IDE.
## When to use
- Running JUnit tests in a `:test` task
- Host mode tests that do not use the desktop app
## Device requirements
When running host JVM unit tests, make sure you have only one Android device,
Android emulator, or iOS simulator running at once. If running a web test,
ensure all iOS and Android devices are not connected. Maestro auto-discovers the
currently running device, so multiple devices can cause failures.
The desktop app supports multiple connected devices.
## Running tests
Use Gradle to run a module or a single test:
```bash
./gradlew :<module>:test
./gradlew :<module>:test --tests "TestClass"
```
If you want Android on-device instrumentation tests instead, see
[Android On-Device Testing](android_on_device.md).