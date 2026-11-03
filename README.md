# EEZY DRAW

Native Android project foundation for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). This implements Part A: project structure, architecture, and serializable data model. The app launches a minimal Compose shell; drawing tools, project management, storage, rendering, and other product features are not implemented.

## Project

```text
EezyDraw/
├── app/                         Android application and Compose shell
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/eezydraw/
│       │   ├── MainActivity.kt
│       │   └── ui/              App composable and theme
│       └── res/                 Strings, themes, launcher drawable, backup policy
├── core/model/                  Android-independent Kotlin/JVM module
│   └── src/
│       ├── main/kotlin/com/eezydraw/core/model/
│       │   ├── Ids.kt
│       │   ├── Geometry.kt
│       │   ├── Project.kt
│       │   ├── ProjectDocument.kt
│       │   ├── Elements.kt
│       │   ├── Library.kt
│       │   ├── Views.kt
│       │   ├── Coordination.kt
│       │   └── Settings.kt
│       └── test/kotlin/com/eezydraw/core/model/
├── docs/ARCHITECTURE.md          Model conventions and future boundaries
├── gradle/                      Version catalog and Gradle wrapper
├── build.gradle.kts
└── settings.gradle.kts
```

The only module dependency is `:app → :core:model`. See [architecture and data model](docs/ARCHITECTURE.md) for relationships, coordinate conventions, and the planned persistence boundary.

## Open in Android Studio

Use Android Studio Meerkat or newer with Android Gradle Plugin 8.9 support. The project pins:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.9.2 |
| Gradle wrapper | 8.13 |
| Kotlin / Compose compiler plugin | 2.1.20 |
| Compose BOM | 2025.04.01 |
| Compile / target SDK | Android 35 |
| Minimum SDK | Android 26 |
| JVM bytecode target | 17 |

1. Open this repository root as a project.
2. Select a JDK 17 or 21 installation for Gradle, such as Android Studio's bundled JDK. A JRE alone is insufficient.
3. Install Android SDK Platform 35 and Android SDK Build-Tools 35.0.0 through SDK Manager; accept the SDK licences.
4. Let Android Studio create the ignored `local.properties` SDK path, then sync Gradle.
5. Select the `app` run configuration and an Android 26+ device or emulator. The launch screen displays the project foundation shell.

No credentials, environment files, backend services, or runtime permissions are required by this scaffold. Internet access to the standard Google, Maven Central, and Gradle repositories is needed for initial dependency downloads. Do not commit `local.properties` or machine-specific SDK paths.

## Command line

From the repository root, with a full JDK on `JAVA_HOME` and the Android SDK configured through `ANDROID_HOME` or `local.properties`:

```sh
./gradlew :core:model:test
./gradlew :app:assembleDebug :app:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device:

```sh
./gradlew :app:installDebug
```

The model tests check serialization of polymorphic records, shared wall references, large-coordinate precision, and invalid model values. They do not exercise drawing, file I/O, Room, or Android UI behavior. Android runtime, gesture, screenshot, and performance tests belong to the later feature implementations.

## Foundation validation

Validated in the cloud environment on 2026-11-03 with a full JDK 21, Android SDK 35, and the pinned Gradle wrapper:

- `:core:model:test`: 5 tests passed, with no failures or skips.
- `:app:assembleDebug`: debug APK built successfully; package `com.eezydraw`, minimum API 26, target API 35, and launcher activity verified in the APK metadata.
- `:app:lintDebug`: passed with no issues found.

The APK has not been launched on a device or emulator. These checks validate the scaffold and data representation, not the product features described in `SPEC.md`.

## Current limits

This foundation contains data declarations and a launchable UI shell. Room entities/DAOs, MVVM feature state, geometry operations, rendering, import/export codecs, sync, and the built-in library catalogue are future work. A data type for an underlay, route, clash, or view does not provide the corresponding feature. JSON serialization supplies a data representation, not a durable project file format or migration implementation.
