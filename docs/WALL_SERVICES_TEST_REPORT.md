# Wall services validation

Validated on 2026-11-08 with the full JDK 21, Android SDK 35 and the pinned Gradle 8.13 toolchain.

| Check | Result |
| --- | --- |
| Model JVM tests | 51 passed |
| Geometry JVM tests | 93 passed |
| Editing JVM tests | 215 passed |
| Storage / Robolectric tests | 24 passed |
| App / Compose / Robolectric tests | 240 passed |
| Debug APK | Built successfully |
| App debug lint | Passed, zero issues |
| Storage debug lint | Passed, zero issues |
| Production GLES shader smoke | Passed, no GL errors |

Total: **623 passed, zero failed, zero skipped**. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
python3 scripts/gles_shader_smoke.py
```

The seven new geometry tests check actual removed material volume, overlap union, left/right recess depths, through-openings, requested-depth warnings, capped sections, transformed/mirrored wall boundaries, unchanged nominal thickness, and restoration of the same implicit-room wall envelope when cuts are hidden. Geometry uses the pinned JTS polygon overlay/triangulation dependency; both the Android APK and host JVM exercise its runtime integration.

The six new editing tests check size-correct back boxes, surface/concealed conversion, wall-plane ortho and actual elevation-view ray picking, move/delete/host-follow behavior, lock rejection, undo/redo, ceiling feeds and device anchors, feed cleanup on deletion, stock penetration geometry, and chase clearance around the curved centreline of a wall-mounted trunking bend.

The new native storage regression uses the actual placement/routing engines to build a wall with a deep device recess, concealed ceiling feed and circular penetration. It verifies source attachments, derived schedule rows, warnings and settings through real Room/revision save, database reopen and portable ZIP import.

The two new Compose regressions check the wall-routing launcher, mounting choice, ghost/cancellation, wall-service selection, contractor schedule, configurable warning threshold, section view and undoable visibility settings. The native EGL surface is substituted as in existing canvas interaction tests. Prior library/ghost assertions now verify that default recessed device fronts are flush with the finish face rather than projecting the entire device in front of it; surface-mounted items retain their earlier behavior.

No emulator or physical tablet was attached. Native GPU/lifecycle behavior, actual touch/stylus use, large-cut scene performance, structural adequacy of chases, equipment installation and firestopping remain device/engineering QA. The depth threshold is an advisory check, not structural certification. Arbitrary saved-view section boxes and cut planes remain separate work; the implemented wall section is horizontal at a configurable AFFL height.
