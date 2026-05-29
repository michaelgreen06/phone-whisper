# Repo Notes

## Java / Gradle

- Use JDK 17 for this repo.
- Do not assume the system `java` is compatible.
- On this machine, plain `./gradlew ...` may fail under Java 25 before app code even builds.

## Preferred Commands

- Use `make build` instead of raw `./gradlew assembleDebug`.
- Use `make test` instead of raw `./gradlew testDebugUnitTest`.
- Use `make adb-reinstall` for the fast install-and-launch loop.
- Use `make adb-logcat` for app audio-routing logs.

## Preferred Dev Loop

- Prefer the ADB iteration loop for feature work and debugging.
- Typical flow: `make adb-reinstall`, reproduce on device, then `make adb-logcat`.
- Assume the connected Android device is the primary test target unless local repo context says otherwise.

The `Makefile` exports the expected toolchain values:

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` when available
- `ANDROID_SDK_ROOT=$ANDROID_HOME`
- `GRADLE_USER_HOME=$REPO/.gradle-local`

## Why

This repo hit a repeatable Gradle/Kotlin startup failure under Java 25:

- `java.lang.IllegalArgumentException: 25.0.2`

That was a toolchain problem, not an app bug. The supported workflow in this repo is therefore:

1. Use the checked-in `Makefile` targets.
2. Stay on JDK 17 unless the Gradle/Android toolchain is intentionally upgraded.
