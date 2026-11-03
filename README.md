# EEZY DRAW

Native Android project for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). It includes vector branding/startup, a durable local project library, native project save/load/import/export, Settings and Help, plus a floating-origin 2D canvas and orbit 3D wireframe viewer. Geometry editing, coordination engines and external CAD codecs remain future work.

## Project

```text
EezyDraw/
├── app/                         Android application, Compose UI and GLES 2 renderer
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/eezydraw/
│       │   ├── MainActivity.kt
│       │   └── ui/              Startup, projects, Settings/Help, theme, canvas
│       └── res/                 Branding, splash/launcher icons, strings, backup policy
├── core/data/                   Room catalogue/preferences, revision files, native archives
├── core/geometry/               Scene compilation, Double cameras, spatial index and snaps
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
├── docs/ARCHITECTURE.md          Module boundaries, canvas, storage and model conventions
├── gradle/                      Version catalog and Gradle wrapper
├── build.gradle.kts
└── settings.gradle.kts
```

Module dependencies are `:app → :core:data → :core:model` and `:app → :core:geometry → :core:model`, with `:app` also using the model directly. See [architecture and data model](docs/ARCHITECTURE.md) for coordinate conventions and the canvas/persistence boundaries.

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
5. Select the `app` run configuration and an Android 26+ device or emulator with OpenGL ES 2 support. The animated splash transitions to the Start Menu; create or continue a project to open its canvas.

No credentials, environment files, backend services, or broad filesystem runtime permissions are required. File/folder access uses Android's system picker. Internet access to the standard Google, Maven Central, and Gradle repositories is needed for initial dependency downloads. Do not commit `local.properties` or machine-specific SDK paths.

## Command line

From the repository root, with a full JDK on `JAVA_HOME` and the Android SDK configured through `ANDROID_HOME` or `local.properties`:

```sh
./gradlew :core:model:test :core:geometry:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device:

```sh
./gradlew :app:installDebug
```

Model/geometry tests run on the JVM. Room/file/archive tests use real temporary storage under Robolectric. Compose tests cover startup, project/settings/help workflows and canvas controls/gestures; the canvas tests replace the native surface to isolate interaction state. Thumbnail tests decode real local PNG fixtures. These checks do not establish Android GPU rendering, physical tablet performance or external URI permission behavior.

## Project workflow

Create a project with a name, template and display units. Office, Residential and Data center supply initial room footprints; Empty supplies a blank document. Creation, Continue, recent cards and native-file import open the drawing canvas. Its top bar offers Save, Export and Project details; details supports metadata/unit editing and Back returns to the same camera. Returning from a dirty project to the menu saves before leaving; failed saves keep the draft open. Continue and Recent Projects use the persisted catalogue and generated thumbnails.

The library is stored privately on this device using Room metadata and atomic, checked document revisions. Export makes a portable `.eezydraw` ZIP containing the document and its referenced attachments. Open from file validates and imports a separate local copy, never silently replacing an existing project. Keep exported copies before uninstalling the app, which removes the private library.

Settings persist across restarts. Units/precision/snaps/grid/service settings supply defaults for new projects; existing projects retain their own settings and changing display units never rescales geometry. Canvas units/snap changes are project edits and use normal save/autosave. Theme, stylus/touch mode, gesture sensitivity, autosave (zero means off), backup and save-folder behavior work now; editing/undo/quality/export preferences are retained for their later engines. The selected save folder receives additional versioned archive copies; local storage remains available if the folder is disconnected or permission is revoked. External copies are not automatically pruned. Native archives remain complete; drawing-export visibility options do not remove attachments from project saves.

Backups retain the preceding valid saved revision when enabled. On a damaged primary, explicitly choose backup recovery; the app does not silently substitute an older project. Help explains current workflows, gesture/shortcut references, about/version and privacy. Feedback opens a share chooser only after explicit submission; it attaches no projects and sends nothing automatically.

## Canvas controls

| Action | Control |
| --- | --- |
| Pan the plan | Drag with one finger; two-finger drag also pans |
| Zoom the plan | Pinch; the point under the gesture stays in place |
| Inspect coordinates/snaps | Hover, or enable Snap probe and drag; a stylus in Stylus mode probes without panning |
| Set a snap reference | Tap the plan; Clear reference removes it |
| Configure snaps | Snaps toolbar/status control; grid, endpoint, midpoint, intersection, perpendicular and ortho |
| Fit the drawing | Zoom extents fits geometry for the selected level |
| Fit a rectangle | Enable Zoom window, then drag the desired rectangle in 2D or 3D |
| Orbit in 3D | Switch to 3D and drag; two fingers pan/pinch, or select Pan for single-drag panning |
| Align the 3D view | Tap an oriented view-cube face or Top, Front, Side or Isometric; choose Perspective or Orthographic |
| Change units/level | Bottom status bar; All levels includes every level, while a chosen level also includes unassigned geometry |

The plan includes an adaptive grid, vector rulers and a coordinate/snap readout in project units. The 3D status reports the camera target. Perpendicular and ortho need a tap reference: perpendicular finds the projection onto a finite edge, while ortho holds a horizontal/vertical construction axis. Grid spacing and snap tolerance remain physical metre values behind the chosen display units. Snaps inspect the projected wireframe; they do not create or modify geometry. Cameras, level/view choices and the reference survive Project details navigation and activity recreation; they are not yet saved into portable project views.

Geometry and its spatial index are prepared off the UI thread. The GLES 2 surface batches immutable chunk buffers, subtracts a Double camera origin before Float conversion, culls offscreen chunks, and renders on demand. Camera gestures reuse those buffers. Nearby snap queries have bounded work, including intersection checks over the nearest 64 edges. These are implementation safeguards for large drawings; measured tablet frame rates and memory remain part of device QA.

## Validation

Verified on 2026-11-04 with the pinned toolchain:

- 96 tests passed with no failures, errors or skips: 5 model, 43 geometry, 9 storage, and 39 app/Compose/ViewModel tests.
- Debug APK assembly passed; app and data-module lint both reported no issues.
- A Mesa GLES2 smoke check passed for the production shaders, vertex-buffer layout, colors, plan elevation projection and 3D depth occlusion, with no GL errors.

The camera regressions include 1 mm extents, deep window zoom, large-coordinate precision, full-height first-entry 3D framing, and gesture anchoring. Project creation → canvas → Back → Continue is covered through actual app navigation with only the native surface substituted. These results do not measure physical tablet FPS or Android EGL lifecycle behavior.

The suite covers model serialization/invariants; Room reopening and real file/archive/backup/failure paths; ViewModel/autosave/restoration; Compose startup, creation/details, Settings, Help and thumbnails; and camera, snap and scene conversion regressions. Geometry tests include large-coordinate floating origins, all model shape variants, scene-budget rejection and deterministic 100,000-segment spatial-query checks, including dense overlapping edges. Canvas Compose gesture/control tests use a substitute surface and inspect real camera/snap state rather than claiming native GPU output.

The production vertex/fragment shaders have also passed a software Mesa GLES 2 smoke check for buffer layout, colour, plan-Z flattening and 3D depth without GL errors. That check does not establish Android surface lifecycle or physical-device frame rates. The manual QA below remains required.

## Manual QA plan

The following remain device/emulator checks, not claims of completed testing:

- Cold launch on API 26/35, disabled animations, rotation, theme/system bars, phone/tablet layouts, large text, RTL, and TalkBack.
- Create each template/unit choice, edit metadata, save, restart the process and reopen via Continue and Recent Projects; inspect regenerated previews.
- Confirm create/continue/recent/import open the canvas, Project details returns to the same camera, and rotation restores the active view/level/reference.
- On a tablet, check one-finger pan, two-finger pan/pinch, stylus hover/probe, gesture sensitivity and view-cube taps; try rapid direction changes and fingers joining/leaving a gesture.
- Open geometry with endpoints, midpoints, crossing lines and multiple levels; check all six snaps, disabled snapping, tolerance at different zooms, negative/large coordinates, perpendicular/ortho references, units and status values. Check global/unassigned geometry remains visible with a selected level.
- Check 2D/3D zoom extents/window, all view presets, perspective/orthographic framing, depth, grid/rulers and light/dark contrast. Confirm tiny window drags do not jump the camera.
- On API 26/35, background/resume and rotate the native surface, force EGL context recreation where possible, reopen different projects, and check renderer errors are visible without damaging saved data.
- Profile a large imported drawing on target tablets: scene/index load time, pan/pinch frame-time percentiles, visible chunk count, GPU/CPU memory and repeated open/close/background cycles. Include dense overlaps and large coordinate offsets; report device/model/API and drawing size. No tablet FPS target has been verified in cloud tests.
- Export/open a native archive with attachments through real SAF providers; verify imported copies leave originals intact.
- Select a writable folder, save copies, revoke its grant or disconnect the provider, and verify the private save remains readable and a copy failure is reported.
- Compare autosave on/off while editing, backgrounding and returning; verify explicit save failures retain unsaved changes.
- Damage a primary in a controlled test installation and restore the previous backup; check disabled/no-backup cases and invalid archives.
- Save every Settings category, restart, and verify persistence and physical grid/service-height values across mm/cm/m/in/ft switches.
- Review Help sections and feedback sharing, including cancellation and missing-handler behavior.

## Current limits

The supported UI language is English and the portable format is native `.eezydraw` v1. Archive limits are 32MiB compressed/expanded, 8MiB document JSON, 16MiB per attachment, and 256 entries. The wireframe viewer accepts at most 500,000 semantic/subdivided render edges and 16,384 spatial chunks, with a maximum single-edge axis span of 64,000,000m; oversized scenes display an error instead of truncating. Plan scale is bounded to `1e-6..1e7` pixels/metre. The virtual canvas has no fixed page boundary, but finite coordinate precision and scene/memory limits still apply.

Curves use up to 64 segments per turn, spline degree is capped at 32, hatches show boundaries, routes show centrelines, and annotation previews use a limited stroke alphabet. Snaps operate in 2D on tessellated edges; dense-query budgets may omit a candidate. Geometry creation/selection/editing, solid/shaded/realistic rendering, saved-view management, underlays, cloud synchronization, coordination, a built-in component catalogue and DWG/DXF/PDF/IFC codecs remain future work. Autosave runs during the app/ViewModel lifetime, not as a background sync service; unsaved drafts are not guaranteed to survive process death.
