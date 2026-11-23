# Verification & Testing Guardrails

- **Run unit and managed-device instrumentation tests before completing code changes:** Before declaring a code change complete (skip only for pure docs/comment edits), run both JVM unit tests and the Gradle Managed Device instrumentation suites covering both the minimum supported API level and the current managed API image defined in `app/build.gradle.kts` (`testOptions.managedDevices.localDevices`):
  - `./gradlew testDebugUnitTest`
  - `./gradlew pixel6Api33DebugAndroidTest pixel6Api35DebugAndroidTest` (or all configured local managed devices if updated in `app/build.gradle.kts`)
- **Prefer Gradle Managed Devices over manual AVD management:** Check `app/build.gradle.kts` `testOptions.managedDevices` first rather than manually booting standalone AVDs, and do not disturb or kill any user-attached physical devices or running emulators.
