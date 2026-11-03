# EEZY DRAW

Native Android project for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). It includes vector branding/startup, project creation and a durable local library, native project save/load/import/export, persistent Settings, and Help. Drawing and coordination engines and external CAD codecs remain future work.

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
├── core/data/                   Room catalogue/preferences, revision files, native archives
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

Module dependencies are `:app → :core:data → :core:model`, with `:app` also using the model directly. See [architecture and data model](docs/ARCHITECTURE.md) for relationships, coordinate conventions, and the planned persistence boundary.

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
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device:

```sh
./gradlew :app:installDebug
```

Model tests cover polymorphic serialization, shared wall references, precision, and invariants. Startup tests use Robolectric and the Compose test clock on the JVM; thumbnail tests decode real local PNG fixtures. These tests do not establish physical-device rendering, external URI permission grants, project file I/O, Room, or drawing behavior.

## Project workflow

Create a project with a name, template and display units. Office, Residential and Data center supply initial room footprints; Empty supplies a blank document. Project details supports metadata/unit editing and Save/Export. Returning from a dirty project saves before leaving; failed saves keep the draft open. Continue and Recent Projects use the persisted catalogue and generated thumbnails.

The library is stored privately on this device using Room metadata and atomic, checked document revisions. Export makes a portable `.eezydraw` ZIP containing the document and its referenced attachments. Open from file validates and imports a separate local copy, never silently replacing an existing project. Keep exported copies before uninstalling the app, which removes the private library.

Settings persist across restarts. Units/precision/snaps/grid/service settings are defaults for new projects, not retroactive geometry changes. Theme, autosave (zero means off), backup and save-folder behavior work now; engine-dependent input/undo/quality/export preferences are retained for later drawing features. The selected save folder receives additional versioned archive copies; local storage remains available if the folder is disconnected or permission is revoked. External copies are not automatically pruned. Native archives remain complete; drawing-export visibility options do not remove attachments from project saves.

Backups retain the preceding valid saved revision when enabled. On a damaged primary, explicitly choose backup recovery; the app does not silently substitute an older project. Help explains current workflows, gesture/shortcut references, about/version and privacy. Feedback opens a share chooser only after explicit submission; it attaches no projects and sends nothing automatically.

## Validation

Verified on 2026-11-04 with JDK 21, SDK 35, and the pinned Gradle wrapper:

- `:core:model:test`: 5 passed.
- `:core:data:testDebugUnitTest`: 9 passed, including database reopening, attachments/archive checks, corruption/backup recovery, failed writes, and cancellation during catalogue publication.
- `:app:testDebugUnitTest`: 33 passed across ViewModel/autosave/save-failure behavior, creation/details, Settings validation/restoration, Help, startup and thumbnails.
- `:app:assembleDebug`: passed.
- `:app:lintDebug` and `:core:data:lintDebug`: passed with no issues.

All 47 tests completed with zero failures, errors or skips. Robolectric tests and generated review captures do not establish physical-device or external-provider behavior; the manual QA below remains unrun.

## Manual QA plan

The following remain device/emulator checks, not claims of completed testing:

- Cold launch on API 26/35, disabled animations, rotation, theme/system bars, phone/tablet layouts, large text, RTL, and TalkBack.
- Create each template/unit choice, edit metadata, save, restart the process and reopen via Continue and Recent Projects; inspect regenerated previews.
- Export/open a native archive with attachments through real SAF providers; verify imported copies leave originals intact.
- Select a writable folder, save copies, revoke its grant or disconnect the provider, and verify the private save remains readable and a copy failure is reported.
- Compare autosave on/off while editing, backgrounding and returning; verify explicit save failures retain unsaved changes.
- Damage a primary in a controlled test installation and restore the previous backup; check disabled/no-backup cases and invalid archives.
- Save every Settings category, restart, and verify persistence and physical grid/service-height values across mm/cm/m/in/ft switches.
- Review Help sections and feedback sharing, including cancellation and missing-handler behavior.

## Current limits

The supported UI language is English and the portable format is native `.eezydraw` v1. Archive limits are 32MiB compressed/expanded, 8MiB document JSON, 16MiB per attachment, and 256 entries. Full drawing/geometry tools, rendering, cloud synchronization, advanced coordination, built-in component catalogue, and DWG/DXF/PDF/IFC codecs remain future work. Gestures and engine-related settings describe or store defaults for those future tools; they do not implement them. Autosave runs during the app/ViewModel lifetime, not as a background sync service; unsaved drafts are not guaranteed to survive process death.
