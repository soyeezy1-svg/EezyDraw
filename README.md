# EEZY DRAW

Native Android project for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). It includes the project foundation and serializable data model plus vector branding, an animated splash transition, and a responsive Start Menu. Project creation/opening, storage, drawing, rendering, and import/export remain future work.

## Project

```text
EezyDraw/
├── app/                         Android application and startup presentation
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/eezydraw/
│       │   ├── MainActivity.kt
│       │   └── ui/              Startup flow, Start Menu, and theme
│       └── res/                 Branding, splash/launcher icons, strings, backup policy
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
5. Select the `app` run configuration and an Android 26+ device or emulator. The animated splash transitions to the Start Menu.

No credentials, environment files, backend services, or runtime permissions are required by this scaffold. Internet access to the standard Google, Maven Central, and Gradle repositories is needed for initial dependency downloads. Do not commit `local.properties` or machine-specific SDK paths.

## Command line

From the repository root, with a full JDK on `JAVA_HOME` and the Android SDK configured through `ANDROID_HOME` or `local.properties`:

```sh
./gradlew :core:model:test :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device:

```sh
./gradlew :app:installDebug
```

Model tests cover polymorphic serialization, shared wall references, precision, and invariants. Startup tests use Robolectric and the Compose test clock on the JVM; thumbnail tests decode real local PNG fixtures. These tests do not establish physical-device rendering, external URI permission grants, project file I/O, Room, or drawing behavior.

## Validation

Validated on 2026-11-03 with JDK 21, Android SDK 35, and the pinned Gradle wrapper:

- `:core:model:test`: 5 tests passed; no failures, errors, or skips.
- `:app:testDebugUnitTest`: 11 tests passed; no failures, errors, or skips. Tests cover timed splash-to-menu transition, completed-startup restoration, Settings selection/dialog restoration, Help dismissal, action callbacks, empty/recent states, compact large-text layouts, and bounded/corrupt/missing/remote thumbnail handling.
- `:app:assembleDebug`: debug APK built successfully. APK metadata confirms `com.eezydraw.MainActivity`, minimum API 26, and target API 35.
- `:app:lintDebug`: passed with no issues found.

Compose and bitmap tests run against Robolectric API 28. Native-rendered tablet and 1.5× text-scale phone previews were exported under `app/build/outputs/previews` and visually reviewed; they are review artifacts, not pixel-baseline tests. The APK has not been run on a physical device or emulator. System splash/launcher rendering, API 26/35 runtime behavior, and disabled-animation behavior still require the manual checks below.

## Startup behavior

The original faceted E mark is included as [SVG](docs/assets/eezy-draw-logo.svg) and Android vector assets, with adaptive and monochrome launcher icons. A static system splash hands off to a 1.2-second Compose logo animation and a 450ms fade/slide into the menu. System animation duration settings apply. Completed startup does not replay on activity recreation.

The menu contains Create New Project, Continue Project, Recent Projects with thumbnail support, Open from file, Templates, and top-right Settings and Help. At 840dp and wider it uses two columns; narrower windows use a scrolling list. Production starts with an empty recent list and disabled Continue. A host can inject ordered project metadata/local thumbnail URIs and handle typed navigation actions. Missing/unreadable previews show a document monogram, not an invented drawing.

Settings changes the appearance for the saved activity session; Help provides startup guidance. Other actions display honest availability dialogs until project creation/loading, template application, and the editor are implemented. The menu currently has no project repository or durable history.

## Manual startup QA plan

These checks still require a device or emulator and have **not** been completed by the JVM tests:

- Cold launch on API 26 and 35; inspect system splash, animated logo, transition, adaptive/themed launcher masks, and menu.
- Disable system animations; confirm there is no forced animation wait.
- Rotate after startup and with a dialog open; verify the splash stays completed and dialog state survives.
- Compare phone/tablet windows around 840dp, portrait/landscape, cutouts, larger fonts, and RTL.
- Check Light/Dark/Device appearance, contrast, TalkBack labels, and Settings/Help touch targets.
- Supply recent-project state with valid/missing/invalid local thumbnail URIs; check previews/fallback, callbacks, and empty-state Continue behavior.

## Current limits

This slice implements startup presentation and data declarations. Project creation/persistence, a complete Settings feature, Room entities/DAOs, editor ViewModels, geometry operations, rendering, import/export codecs, sync, and the built-in library catalogue are future work. JSON serialization is a data representation, not a durable project file format or migration implementation.
