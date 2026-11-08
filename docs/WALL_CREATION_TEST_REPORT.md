# Room and wall creation validation

Validated with JDK 21, Android SDK 35 and the pinned Gradle 8.13 toolchain.

| Check | Result |
| --- | --- |
| Model JVM tests | 51 passed |
| Geometry JVM tests | 93 passed |
| Editing JVM tests | 218 passed |
| Storage / Robolectric tests | 25 passed |
| App / Compose / Robolectric tests | 243 passed |
| Debug APK | Built successfully |
| App debug lint | Passed, zero issues |
| Storage debug lint | Passed, zero issues |

Total: **630 passed, zero failed, zero skipped**. APK: `app/build/outputs/apk/debug/app-debug.apk`.

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

The three editing regressions cover automatic/manual room-to-wall generation, default-on settings, material/alignment options, level ownership, undo/redo, shared wall identity, shared-corner updates, height scaling, and standalone walls. They also check corner profiles and cleanup after deleting a chained neighbor. The native storage regression saves and reopens generated rooms/walls through the real repository, retaining options, materials, profiles and wall IDs, and checks reconciliation is idempotent.

The three Compose regressions cover automatic Room placement, the direct Generate walls selection action/dialog, chained Wall placement and Cancel, and the exact missing-wall message with its Open Wall tool recovery action. They verify entering/cancelling that action preserves ordinary single-tap sized Box placement. Existing precision-pointer and placement tests exercise the shared input infrastructure; these new canvas tests substitute the GLES surface.

See [the quick manual path](WALL_CREATION_QUICK_TEST.md) for room creation, generation, editing and socket placement. No emulator or physical tablet was attached; native rendering, touch/stylus ergonomics, corner appearance around service cuts and large-project performance still need device QA.
