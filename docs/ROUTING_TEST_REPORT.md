# Connected service routing validation

Validated on 2026-11-07 with full JDK 21, Android SDK 35 and the pinned Gradle 8.13 toolchain.

| Check | Result |
| --- | --- |
| Model JVM tests | 51 passed |
| Geometry JVM tests | 86 passed |
| Editing JVM tests | 209 passed |
| Storage / Robolectric tests | 23 passed |
| App / Compose / Robolectric tests | 238 passed |
| Debug APK | Built successfully |
| App debug lint | Passed, zero issues |
| Storage debug lint | Passed, zero issues |
| Production GLES shader smoke | Passed, no GL errors |

Total: **607 tests passed, zero failed, zero skipped**. The full suite, APK and lint invocation passed with 606 tests; the additional real engine-to-storage integration test and complete storage suite then passed with 23 storage tests. Production code did not change between these runs. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
python3 scripts/gles_shader_smoke.py
```

The eleven new editing regressions exercise all seven route kinds; relative/slab/room-ceiling elevations; ortho; mid-route and cross-level risers; actual 45° bevel and 90° bend geometry; physical fitting-port/section trim agreement; sized offset assemblies; tees/crosses; reducers beside turning fittings; connected endpoint and height edits; lock rejection; undo/redo; support spacing; fitting suppression/replacement; copied-network independence; deleted-section repairs; equipment anchors; hidden-host exclusion; and equipment-height connection risers.

Two new Compose regressions exercise the actual Draw-panel routing launcher, settings bar, native route creation, ghost, height change, remembered settings without navigation, preserved panel layout, project undo/redo, and precision-pointer Place/Cancel. The native surface is substituted, as in the existing canvas interaction suites.

Two new real Room/revision/ZIP regressions retain graph identity, service defaults, risers, fitting overrides/suppression and support intent across reopen/import. One uses the actual routing engine to create a branched duct route with reducers, generated library geometry, physical trims and spaced supports, then checks save/load/import equality and reconciliation idempotence. Storage has a test-only dependency on editing for this integration check.

The full run exposed existing test-fixture races in cold project restore and deferred autosave tests: those constructors used real asynchronous device-library I/O while asserting completion under a test scheduler. They now inject the fixture's existing in-memory library, consistently with other ViewModel tests. Assertions and production initialization behavior remain intact.

No emulator or physical device was attached. Android GPU/lifecycle behavior, physical tablet gestures/performance, actual equipment connector conventions, engineering/fabrication suitability and real SAF providers remain manual QA. These automated results do not certify clash avoidance, hydraulic design or structural support spacing.
